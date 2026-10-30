package dev.relayops.delivery;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WebhookTransport {
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    private final int timeout;
    public WebhookTransport(@Value("${relayops.delivery.timeout-seconds:5}") int timeout) {
        if(timeout<1 || timeout>5) throw new IllegalArgumentException("Invalid delivery timeout"); this.timeout=timeout;
    }
    public record Outcome(Integer status,String error,boolean retry,long retryAfterMillis) { public boolean success() {return status!=null && status>=200 && status<300;} }
    public static String signature(String secret,long timestamp,String deliveryId,String hash) {
        try {
            var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(Base64.getDecoder().decode(secret),"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(("v1\n"+timestamp+"\n"+deliveryId+"\n"+hash).getBytes(StandardCharsets.US_ASCII)));
        } catch(Exception ex) {throw new IllegalStateException("SIGNING_FAILED");}
    }
    public static long retryAfter(String value,Instant now) {
        if(value==null || value.length()>100) return 0;
        try {
            if(!value.strip().matches("[0-9]+")) throw new IllegalArgumentException();
            return new java.math.BigInteger(value.strip()).multiply(java.math.BigInteger.valueOf(1000)).min(java.math.BigInteger.valueOf(3_600_001)).longValue();
        }
        catch(Exception ignored) {try {return Math.min(3_600_001,Math.max(0,Duration.between(now,ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis()));}catch(Exception ex){return 0;}}
    }
    public Outcome send(String endpoint,String delivery,String run,String attempt,String keyId,String secret,String payload,String hash) {
        long timestamp=Instant.now().getEpochSecond();
        var request=HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(timeout))
            .header("Content-Type","application/json; charset=utf-8").header("X-Relay-Delivery-Id",delivery).header("X-Relay-Run-Id",run).header("X-Relay-Attempt-Id",attempt)
            .header("X-Relay-Key-Id",keyId).header("X-Relay-Timestamp",Long.toString(timestamp)).header("X-Relay-Signature",signature(secret,timestamp,delivery,hash))
            .POST(HttpRequest.BodyPublishers.ofString(payload,StandardCharsets.UTF_8)).build();
        var future=client.sendAsync(request,HttpResponse.BodyHandlers.discarding());
        try {
            var response=future.get(timeout,TimeUnit.SECONDS);int code=response.statusCode();
            if(code>=200 && code<300) return new Outcome(code,null,false,0);
            boolean retry=code==408 || code==429 || code>=500;
            return new Outcome(code,"HTTP_"+code,retry,code==429?Math.max(0,retryAfter(response.headers().firstValue("Retry-After").orElse(null),Instant.now())):0);
        } catch(InterruptedException ex) {Thread.currentThread().interrupt(); return new Outcome(null,"NETWORK_UNKNOWN",true,0);}
        catch(Exception ex) {return new Outcome(null,"NETWORK_UNKNOWN",true,0);}
        finally {if(!future.isDone())future.cancel(true);}
    }
    @PreDestroy public void close() {client.shutdownNow();}
}
