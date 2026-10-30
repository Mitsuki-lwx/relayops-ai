package dev.relayops.job;

import com.sun.net.httpserver.HttpServer;
import dev.relayops.common.ApiException;
import dev.relayops.common.PayloadCipher;
import dev.relayops.delivery.*;
import dev.relayops.messaging.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RELAYOPS_MESSAGING_TESTS",matches="true")
@SpringBootTest(properties={"relayops.consumer-enabled=false","relayops.relay-enabled=false","relayops.recovery-enabled=false","relayops.delivery.timeout-seconds=1"})
class DeliveryIntegrationTest {
    static final HttpServer receiver;
    static final AtomicInteger calls=new AtomicInteger();
    static volatile int response=200,failFirst;
    static volatile String retryAfter;
    static volatile boolean disconnect;
    static volatile long delayMillis;
    static volatile java.util.concurrent.CountDownLatch requestArrived, responseRelease;
    static final List<byte[]> bodies=new java.util.concurrent.CopyOnWriteArrayList<>();
    static final List<Map<String,String>> headers=new java.util.concurrent.CopyOnWriteArrayList<>();
    static {
        try {
            receiver=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            receiver.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(Thread.ofPlatform().daemon().factory()));
            receiver.createContext("/",exchange->{
                try(exchange) {
                    int n=calls.incrementAndGet();bodies.add(exchange.getRequestBody().readAllBytes());
                    var h=new java.util.HashMap<String,String>();for(String name:List.of("Delivery-Id","Run-Id","Attempt-Id","Key-Id","Timestamp","Signature"))h.put(name,exchange.getRequestHeaders().getFirst("X-Relay-"+name));headers.add(h);
                    if(disconnect)return;
                    var arrived=requestArrived;var release=responseRelease;long delay=delayMillis;
                    if(arrived!=null)arrived.countDown();
                    try {
                        if(release!=null)release.await(3,java.util.concurrent.TimeUnit.SECONDS);
                        if(delay>0)Thread.sleep(delay);
                    } catch(InterruptedException ex) {Thread.currentThread().interrupt();return;}
                    if(retryAfter!=null)exchange.getResponseHeaders().set("Retry-After",retryAfter);
                    if(response==302)exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+receiver.getAddress().getPort()+"/redirect");
                    int status=n<=failFirst?500:response;
                    exchange.sendResponseHeaders(status,-1);
                }
            });receiver.start();
        } catch(Exception ex){throw new ExceptionInInitializerError(ex);}
    }
    @DynamicPropertySource static void port(DynamicPropertyRegistry registry){registry.add("relayops.delivery.demo-port",()->receiver.getAddress().getPort());}
    @AfterAll static void stop(){receiver.stop(0);}
    @Autowired JdbcTemplate jdbc;
    @Autowired JobService jobs;
    @Autowired JobWorker ai;
    @Autowired DeliveryWorker worker;
    @Autowired DeliveryStore store;
    @Autowired DestinationService destinations;
    @Autowired PayloadCipher cipher;
    @Autowired OutboxStore outbox;
    @Autowired OutboxRelay relay;
    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin admin;
    @Autowired JsonMapper json;
    @Autowired TransactionTemplate tx;
    @Autowired FakeModel fake;
    String workspace,app,destination;
    @BeforeEach void fixtures() throws Exception {
        try(var c=jdbc.getDataSource().getConnection()){assertEquals("relayops_test",c.getCatalog());}
        assertEquals("relayops-test",rabbit.getConnectionFactory().getVirtualHost());
        jdbc.update("UPDATE ai_job SET parent_job_id=NULL");
        for(var table:List.of("delivery_outbox","delivery_attempt","delivery_run","delivery","job_destination","destination","quarantine_message","outbox_event","audit_event","idempotency_record","ai_result","budget_entry","ai_attempt","ai_job","budget_account"))jdbc.update("DELETE FROM "+table);
        admin.purgeQueue(RabbitConfig.AI_QUEUE,false);admin.purgeQueue(RabbitConfig.DELIVERY_QUEUE,false);
        calls.set(0);bodies.clear();headers.clear();response=200;failFirst=0;retryAfter=null;disconnect=false;delayMillis=0;requestArrived=null;responseRelease=null;
        workspace=jdbc.queryForObject("SELECT id FROM workspace WHERE code='a'",String.class);app=jdbc.queryForObject("SELECT id FROM application WHERE workspace_id=? LIMIT 1",String.class,workspace);
        destination=destinations.create("owner.a",workspace,new DestinationService.Create("验收接收端",DestinationService.CATALOG)).id();
    }
    String id(){return UUID.randomUUID().toString();}
    JobService.CreateJob request(List<String> ids){return new JobService.CreateJob(app,"交付验收","只有结果提交后才发回调。",ids,"zh-CN");}
    ReadyMessage envelope(String table,String aggregate,String kind){return jdbc.queryForObject("SELECT * FROM "+table+" WHERE aggregate_id=? ORDER BY dispatch_generation DESC LIMIT 1",(rs,n)->new ReadyMessage(1,rs.getString("id"),kind,rs.getString("workspace_id"),aggregate,rs.getLong("dispatch_generation"),Instant.now(),rs.getString("trace_id")),aggregate);}
    String create(){String job=jobs.create("owner.a",workspace,id(),request(List.of(destination))).jobId();assertTrue(ai.execute(envelope("outbox_event",job,"AI_EXECUTE")));return job;}
    String run(){return jdbc.queryForObject("SELECT id FROM delivery_run LIMIT 1",String.class);}
    ReadyMessage ready(){return envelope("delivery_outbox",run(),"DELIVERY_SEND");}
    String status(){return jdbc.queryForObject("SELECT status FROM delivery_run WHERE id=?",String.class,run());}
    void due(){jdbc.update("UPDATE delivery_run SET next_attempt_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",run());worker.recover();}

    @Test void targetsAreScopedCanonicalAndPartOfIntentWithoutPartialAdmission() {
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM ai_job",Integer.class);
        assertThrows(ApiException.class,()->jobs.create("owner.a",workspace,id(),request(List.of(id()))));
        assertThrows(ApiException.class,()->jobs.create("owner.a",workspace,id(),request(List.of(destination,destination))));
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM ai_job",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM budget_entry",Integer.class));
        String key=id();var job=jobs.create("owner.a",workspace,key,request(List.of(destination)));
        assertEquals(job,jobs.create("owner.a",workspace,key,request(List.of(destination))));
        assertEquals(409,assertThrows(ApiException.class,()->jobs.create("owner.a",workspace,key,request(List.of()))).status);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM job_destination",Integer.class));
        assertEquals(403,assertThrows(ApiException.class,()->destinations.create("operator.a",workspace,new DestinationService.Create("no",DestinationService.CATALOG))).status);
        assertEquals(403,assertThrows(ApiException.class,()->destinations.reveal("viewer.a",workspace,destination,true)).status);
        assertEquals(404,assertThrows(ApiException.class,()->destinations.reveal("owner.b",workspace,destination,true)).status);
        assertThrows(ApiException.class,()->destinations.reveal("owner.a",workspace,destination,false));
        var secret=destinations.reveal("owner.a",workspace,destination,true);assertFalse(json.writeValueAsString(destinations.list("viewer.a",workspace)).contains(secret.get("secret")));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE operation='DESTINATION_KEY_REVEALED'",Integer.class));
    }
    @Test void resultAndDeliveryRollbackTogetherAndNoTargetMeansNoDelivery() {
        var paragraphs=DocumentInput.paragraphs("只有结果提交后才发回调。");var result=new FakeModel().extract("测试",paragraphs,"zh-CN");
        var accepted=jobs.create("owner.a",workspace,id(),request(List.of(destination)));
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->{
            jdbc.update("INSERT INTO ai_result(job_id,workspace_id,result_cipher) VALUES (?,?,?)",accepted.jobId(),workspace,new byte[]{1});
            store.createForResult(workspace,accepted.jobId(),json.writeValueAsString(result));
            throw new IllegalStateException("injected-after-outbox");
        }));
        for(var table:List.of("ai_result","delivery","delivery_run","delivery_outbox"))assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
        String plain=jobs.create("owner.a",workspace,id(),request(List.of())).jobId();assertTrue(ai.execute(envelope("outbox_event",plain,"AI_EXECUTE")));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM delivery",Integer.class));
        assertTrue(ai.execute(envelope("outbox_event",accepted.jobId(),"AI_EXECUTE")));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM delivery",Integer.class));
    }
    @Test void realBrokerRoutesDeliveryAndRetriesKeepBytesSignatureAndOneModelCall() throws Exception {
        String job=create();long modelCount=fake.callCount();failFirst=2;
        for(int i=0;i<3;i++) {
            relay.publish(outbox.claim());var message=rabbit.receive(RabbitConfig.DELIVERY_QUEUE,5000);assertNotNull(message);
            var e=ReadyMessage.decode(message.getBody(),json);outbox.verify(e);assertEquals("DELIVERY_SEND",e.kind());assertTrue(worker.execute(e));assertTrue(worker.execute(e));
            if(i<2){assertEquals("RETRY_WAIT",status());due();}
        }
        assertEquals("SUCCEEDED",status());assertEquals(3,calls.get());assertEquals(modelCount,fake.callCount());assertEquals(1,jobs.get("owner.a",workspace,job).modelCalls());
        String secret=destinations.reveal("owner.a",workspace,destination,true).get("secret");
        for(int i=0;i<3;i++){assertArrayEquals(bodies.get(0),bodies.get(i));var h=headers.get(i);assertEquals(headers.getFirst().get("Delivery-Id"),h.get("Delivery-Id"));assertEquals(WebhookTransport.signature(secret,Long.parseLong(h.get("Timestamp")),h.get("Delivery-Id"),DocumentInput.hash(new String(bodies.get(i),StandardCharsets.UTF_8))),h.get("Signature"));}
        assertEquals(3,headers.stream().map(h->h.get("Attempt-Id")).distinct().count());
        assertEquals(3,store.list("viewer.a",workspace,job).getFirst().attempts().size());
        assertEquals(404,assertThrows(ApiException.class,()->store.list("owner.b",workspace,job)).status);
    }
    @Test void permanentErrorsRedirectsAndDisablingDoNotSendAgain() {
        create();response=302;assertTrue(worker.execute(ready()));assertEquals("DEAD",status());assertEquals(1,calls.get());worker.recover();assertTrue(worker.execute(ready()));assertEquals(1,calls.get());
    }
    @Test void networkUncertaintyRetriesAndPermanentClientErrorsStop() {
        create();disconnect=true;assertTrue(worker.execute(ready()));assertEquals("RETRY_WAIT",status());assertEquals("UNKNOWN",jdbc.queryForObject("SELECT status FROM delivery_attempt",String.class));
        due();disconnect=false;response=408;assertTrue(worker.execute(ready()));assertEquals("RETRY_WAIT",status());
        due();response=400;assertTrue(worker.execute(ready()));assertEquals("DEAD",status());assertEquals(3,calls.get());
    }
    @Test void retryAfterHandlesDateMalformedAndHugeValuesWithoutOverflow() {
        Instant time=Instant.parse("2026-10-30T00:00:00Z");
        assertEquals(60_000,WebhookTransport.retryAfter("Fri, 30 Oct 2026 00:01:00 GMT",time));
        assertEquals(3_600_001,WebhookTransport.retryAfter("99999999999999999999999999999",time));
        assertEquals(0,WebhookTransport.retryAfter("-3",time));assertEquals(0,WebhookTransport.retryAfter("bad",time));
    }
    @Test void requestTimeoutRemainsUnknownAfterReceiverEventuallyResponds() throws Exception {
        create();delayMillis=1600;long started=System.nanoTime();
        assertTrue(worker.execute(ready()));
        assertTrue(java.time.Duration.ofNanos(System.nanoTime()-started).toMillis()<2500);
        assertEquals("RETRY_WAIT",status());
        assertEquals("UNKNOWN",jdbc.queryForObject("SELECT status FROM delivery_attempt",String.class));
        Thread.sleep(800); // The receiver's late 200 must not change the settled outcome.
        assertEquals("RETRY_WAIT",status());assertEquals(1,calls.get());
    }
    @Test void expiredLeaseCannotBeOverwrittenByOldExecutorsLateSuccess() throws Exception {
        create();var ready=ready();requestArrived=new java.util.concurrent.CountDownLatch(1);responseRelease=new java.util.concurrent.CountDownLatch(1);
        var executing=java.util.concurrent.CompletableFuture.supplyAsync(()->worker.execute(ready));
        try {
            assertTrue(requestArrived.await(2,java.util.concurrent.TimeUnit.SECONDS));
            jdbc.update("UPDATE delivery_run SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",run());
            worker.recover();assertEquals("RETRY_WAIT",status());
        } finally {responseRelease.countDown();}
        assertTrue(executing.get(3,java.util.concurrent.TimeUnit.SECONDS));
        assertEquals("RETRY_WAIT",status());assertEquals("UNKNOWN",jdbc.queryForObject("SELECT status FROM delivery_attempt",String.class));
        due();assertTrue(worker.execute(ready()));assertEquals("SUCCEEDED",status());assertEquals(2,calls.get());
    }
    @Test void disabledOrChangedCatalogAndExpiredPayloadAreNotSent() {
        create();destinations.enable("owner.a",workspace,destination,new DestinationService.Enable(false,0L));assertTrue(worker.execute(ready()));assertEquals("DEAD",status());assertEquals(0,calls.get());
        jdbc.update("UPDATE delivery_run SET status='PENDING' WHERE id=?",run());destinations.enable("owner.a",workspace,destination,new DestinationService.Enable(true,1L));jdbc.update("UPDATE delivery SET endpoint='http://127.0.0.1:1/secret'");assertTrue(worker.execute(ready()));assertEquals("DEAD",status());assertEquals(0,calls.get());
        jdbc.update("UPDATE delivery_run SET status='PENDING' WHERE id=?",run());jdbc.update("UPDATE delivery SET payload_expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND)");assertTrue(worker.execute(ready()));assertEquals(0,calls.get());
    }
    @Test void legalRetryAfterAndAttemptBudgetAreEnforced() {
        create();response=429;retryAfter="60";assertTrue(worker.execute(ready()));assertEquals("RETRY_WAIT",status());assertTrue(jdbc.queryForObject("SELECT next_attempt_at>=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 58 SECOND) FROM delivery_run WHERE id=?",Boolean.class,run()));
        due();retryAfter="3600";assertTrue(worker.execute(ready()));assertEquals("DEAD",status());assertEquals(2,calls.get());
    }
    @Test void sixAttemptLimitAndDeadlinesStopWithoutExtraModelCalls() {
        create();response=500;for(int i=0;i<6;i++){assertTrue(worker.execute(ready()));if(i<5){assertEquals("RETRY_WAIT",status());due();}}
        assertEquals("DEAD",status());assertEquals(6,calls.get());assertEquals(6,jdbc.queryForObject("SELECT attempt_count FROM delivery_run",Integer.class));worker.recover();assertTrue(worker.execute(ready()));assertEquals(6,calls.get());
    }
    @Test void expiredInFlightIsUnknownAndOldGenerationCannotSend() {
        create();var old=ready();jdbc.update("UPDATE delivery_run SET status='IN_FLIGHT',attempt_count=1,lease_token=?,lease_until=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND)",id());
        jdbc.update("INSERT INTO delivery_attempt(id,workspace_id,run_id,attempt_no,key_id) VALUES (?,?,?,1,?)",id(),workspace,run(),destination);
        worker.recover();assertEquals("UNKNOWN",jdbc.queryForObject("SELECT status FROM delivery_attempt",String.class));assertEquals("RETRY_WAIT",status());due();assertTrue(worker.execute(old));assertEquals(0,calls.get());assertTrue(worker.execute(ready()));assertEquals(1,calls.get());assertEquals("SUCCEEDED",status());
        var future=new ReadyMessage(1,id(),"DELIVERY_SEND",workspace,run(),999,Instant.now(),id());assertThrows(ReadyMessage.Invalid.class,()->worker.execute(future));
    }
    @Test void retryUsesFrozenTargetsAndRefusesCurrentlyDisabledTarget() {
        String job=jobs.create("owner.a",workspace,id(),request(List.of(destination))).jobId();jdbc.update("UPDATE ai_job SET status='FAILED' WHERE id=?",job);
        destinations.enable("owner.a",workspace,destination,new DestinationService.Enable(false,0L));assertThrows(ApiException.class,()->jobs.retry("owner.a",workspace,job,id(),new JobService.RetryJob(0L,"复验",true)));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ai_job",Integer.class));destinations.enable("owner.a",workspace,destination,new DestinationService.Enable(true,1L));
        var child=jobs.retry("owner.a",workspace,job,id(),new JobService.RetryJob(0L,"复验",true));assertEquals(destination,jdbc.queryForObject("SELECT destination_id FROM job_destination WHERE job_id=?",String.class,child.jobId()));
    }
}
