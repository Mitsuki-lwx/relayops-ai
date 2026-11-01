package dev.relayops.identity;

import dev.relayops.common.ApiException;
import dev.relayops.messaging.ReadyMessage;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Trusted host-only provisioning. Deliberately has no HTTP controller. */
@Service
public class ProvisioningService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    public ProvisioningService(JdbcTemplate jdbc, PasswordEncoder encoder) {this.jdbc=jdbc;this.encoder=encoder;}
    public record Created(String userId,String tenantId,String workspaceId) {}
    private static ApiException invalid() {return new ApiException(422,"INVALID_INPUT","用户名、密码或公司参数无效");}

    @Transactional
    public Created provision(String username,String password,String companyName,String tenantId) {
        if(username==null || !username.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,79}") || password==null
                || password.codePointCount(0,password.length())<12 || password.getBytes(StandardCharsets.UTF_8).length>72
                || password.isBlank() || password.startsWith("replace_") || password.contains("CHANGE_ME")) throw invalid();
        boolean company=tenantId==null || tenantId.isBlank();
        String name=companyName==null?"":Normalizer.normalize(companyName.strip(),Normalizer.Form.NFC);
        if(company) {
            if(name.isBlank() || name.length()>80 || name.chars().anyMatch(Character::isISOControl))throw invalid();
            tenantId=UUID.randomUUID().toString();
        } else {
            if(!name.isEmpty() || !ReadyMessage.uuid(tenantId))throw invalid();
            if(jdbc.queryForList("SELECT id FROM tenant WHERE id=? FOR UPDATE",String.class,tenantId).isEmpty())throw new ApiException(404,"NOT_FOUND","公司不存在");
        }
        if(!jdbc.queryForList("SELECT id FROM user_account WHERE username=?",String.class,username).isEmpty())throw new ApiException(409,"USERNAME_EXISTS","用户名已存在，不覆盖已有密码");
        String user=UUID.randomUUID().toString();
        try {jdbc.update("INSERT INTO user_account(id,username,password_hash) VALUES (?,?,?)",user,username,encoder.encode(password));}
        catch(DuplicateKeyException ex) {throw new ApiException(409,"USERNAME_EXISTS","用户名已存在，不覆盖已有密码");}
        String space=null;
        if(company)jdbc.update("INSERT INTO tenant(id,name) VALUES (?,?)",tenantId,name);
        jdbc.update("INSERT INTO tenant_member(tenant_id,user_id,role) VALUES (?,?,?)",tenantId,user,company?"ADMIN":"MEMBER");
        if(company) {
            space=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO workspace(id,tenant_id,code,name) VALUES (?,?,?,?)",space,tenantId,"w-"+space.replace("-",""),name+" 工作空间");
            jdbc.update("INSERT INTO workspace_member(workspace_id,user_id,role) VALUES (?,?,'OWNER')",space,user);
            jdbc.update("INSERT INTO application(id,workspace_id,name) VALUES (?,?,?)",UUID.randomUUID().toString(),space,"技术文档提取");
        }
        return new Created(user,tenantId,space);
    }
}
