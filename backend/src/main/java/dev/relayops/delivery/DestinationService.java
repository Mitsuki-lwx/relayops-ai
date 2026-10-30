package dev.relayops.delivery;

import dev.relayops.common.ApiException;
import dev.relayops.common.PayloadCipher;
import dev.relayops.identity.Access;
import dev.relayops.job.DocumentInput;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DestinationService {
    public static final String CATALOG = "demo-local-v1";
    private final JdbcTemplate jdbc;
    private final Access access;
    private final PayloadCipher cipher;
    private final String endpoint;
    public DestinationService(JdbcTemplate jdbc, Access access, PayloadCipher cipher,
            @Value("${relayops.delivery.demo-port:8091}") int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid local receiver port");
        this.jdbc=jdbc; this.access=access; this.cipher=cipher;
        endpoint="http://127.0.0.1:"+port+"/relayops/events";
    }
    public record Destination(String id, String name, String catalogId, String endpoint, boolean enabled, long version) {}
    public record Create(String name, String catalogId) {}
    public record Enable(Boolean enabled, Long expectedVersion) {}
    public boolean current(String catalogId, String address) { return CATALOG.equals(catalogId) && endpoint.equals(address); }
    public List<Map<String,String>> catalog() { return List.of(Map.of("id",CATALOG,"name","本机演示接收端","endpoint",endpoint)); }
    private Access.Member owner(String username,String workspace) {
        var member=access.require(username,workspace,true,true,true);
        if (!member.role().equals("OWNER")) throw new ApiException(403,"FORBIDDEN","只有空间所有者可以管理目的地或读取签名密钥");
        return member;
    }
    public List<Destination> list(String username,String workspace) {
        access.require(username,workspace,false,false,false);
        return jdbc.query("SELECT * FROM destination WHERE workspace_id=? ORDER BY name,id",(rs,n)->new Destination(rs.getString("id"),rs.getString("name"),rs.getString("catalog_id"),rs.getString("endpoint"),rs.getBoolean("enabled"),rs.getLong("version")),workspace);
    }
    @Transactional public Destination create(String username,String workspace,Create request) {
        var member=owner(username,workspace);
        if (request==null || request.name()==null || request.name().isBlank() || request.name().strip().length()>80 || !CATALOG.equals(request.catalogId()))
            throw DocumentInput.invalid("名称为1至80字符，只能选择受控目录");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM destination WHERE workspace_id=?",Integer.class,workspace)>=20)
            throw new ApiException(409,"DESTINATION_LIMIT","每空间最多保存20个目的地");
        String id=UUID.randomUUID().toString(); byte[] random=new byte[32]; new SecureRandom().nextBytes(random);
        jdbc.update("INSERT INTO destination(id,workspace_id,name,catalog_id,endpoint,secret_cipher) VALUES (?,?,?,?,?,?)",id,workspace,request.name().strip(),CATALOG,endpoint,
                cipher.encrypt(Base64.getEncoder().encodeToString(random),workspace+":"+id+":signing-key"));
        audit(workspace,member.userId(),id,"DESTINATION_CREATED");
        return list(username,workspace).stream().filter(d->d.id().equals(id)).findFirst().orElseThrow();
    }
    @Transactional public void enable(String username,String workspace,String id,Enable request) {
        var member=owner(username,workspace);
        if (request==null || request.enabled()==null || request.expectedVersion()==null) throw DocumentInput.invalid("需要启用状态和当前版本");
        var versions=jdbc.queryForList("SELECT version FROM destination WHERE workspace_id=? AND id=? FOR UPDATE",Long.class,workspace,id);
        if (versions.isEmpty()) throw new ApiException(404,"NOT_FOUND","目的地不可访问");
        if (!versions.getFirst().equals(request.expectedVersion())) throw new ApiException(409,"VERSION_CONFLICT","目的地已变化，请刷新");
        jdbc.update("UPDATE destination SET enabled=?,version=version+1 WHERE id=?",request.enabled(),id);
        audit(workspace,member.userId(),id,request.enabled()?"DESTINATION_ENABLED":"DESTINATION_DISABLED");
    }
    @Transactional public Map<String,String> reveal(String username,String workspace,String id,boolean acknowledged) {
        var member=owner(username,workspace);
        if (!acknowledged) throw DocumentInput.invalid("请明确确认读取签名密钥");
        var keys=jdbc.queryForList("SELECT secret_cipher FROM destination WHERE workspace_id=? AND id=? FOR SHARE",byte[].class,workspace,id);
        if (keys.isEmpty()) throw new ApiException(404,"NOT_FOUND","目的地不可访问");
        var secret=cipher.decrypt(keys.getFirst(),workspace+":"+id+":signing-key");
        audit(workspace,member.userId(),id,"DESTINATION_KEY_REVEALED");
        return Map.of("keyId",id,"secret",secret);
    }
    public void freeze(String workspace,String job,List<String> ids) {
        for (var id:ids) {
            var rows=jdbc.query("SELECT * FROM destination WHERE workspace_id=? AND id=? AND enabled=TRUE FOR SHARE",(rs,n)->new Destination(rs.getString("id"),rs.getString("name"),rs.getString("catalog_id"),rs.getString("endpoint"),true,rs.getLong("version")),workspace,id);
            if (rows.isEmpty() || !current(rows.getFirst().catalogId(),rows.getFirst().endpoint())) throw new ApiException(422,"DESTINATION_UNAVAILABLE","目的地未启用或目录已变更");
            var d=rows.getFirst();
            jdbc.update("INSERT INTO job_destination(workspace_id,job_id,destination_id,catalog_id,endpoint,name) VALUES (?,?,?,?,?,?)",workspace,job,id,d.catalogId(),d.endpoint(),d.name());
        }
    }
    private void audit(String workspace,String actor,String resource,String operation) {
        jdbc.update("INSERT INTO audit_event(workspace_id,actor_id,resource_id,operation) VALUES (?,?,?,?)",workspace,actor,resource,operation);
    }
}
