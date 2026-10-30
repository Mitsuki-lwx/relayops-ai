package dev.relayops.organization;

import dev.relayops.common.ApiException;
import dev.relayops.identity.Access;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class OrganizationService {
    private final JdbcTemplate jdbc;
    private final Access access;
    private final JsonMapper json;
    public OrganizationService(JdbcTemplate jdbc, Access access, JsonMapper json) { this.jdbc=jdbc; this.access=access; this.json=json; }
    public record NewUnit(String name, String kind, String parentId) {}
    public record NewWorkspace(String name) {}
    public record Membership(Boolean active) {}
    public record Grant(String role) {}
    public record Created(String id) {}
    private record Actor(String id, String role) {}

    public List<Map<String,Object>> list(String username) {
        return jdbc.queryForList("""
                SELECT t.id,t.name,m.role FROM tenant t JOIN tenant_member m ON m.tenant_id=t.id
                JOIN user_account u ON u.id=m.user_id WHERE u.username=? AND u.enabled=TRUE AND m.active=TRUE ORDER BY t.name,t.id
                """, username);
    }
    private Actor admin(String username, String tenantId, boolean lock) {
        if (lock) access.lockTenant(tenantId);
        var actors=jdbc.query("""
                SELECT u.id,m.role FROM tenant_member m JOIN user_account u ON u.id=m.user_id
                WHERE m.tenant_id=? AND u.username=? AND u.enabled=TRUE AND m.active=TRUE
                """, (rs,row)->new Actor(rs.getString("id"),rs.getString("role")),tenantId,username);
        if (actors.isEmpty()) throw missing();
        var actor=actors.getFirst();
        if (!actor.role().equals("ADMIN")) throw new ApiException(403,"FORBIDDEN","需要公司管理员权限");
        return actor;
    }
    public Map<String,Object> details(String username, String tenantId) {
        var actor=admin(username,tenantId,false);
        return Map.of(
                "units",jdbc.queryForList("SELECT id,name,kind,parent_id AS parentId FROM organization_unit WHERE tenant_id=? ORDER BY name,id",tenantId),
                "members",jdbc.queryForList("SELECT u.id,u.username,m.role,m.active FROM tenant_member m JOIN user_account u ON u.id=m.user_id WHERE m.tenant_id=? ORDER BY u.username",tenantId),
                "memberships",jdbc.queryForList("SELECT unit_id AS unitId,user_id AS userId FROM organization_unit_member WHERE tenant_id=? AND active=TRUE",tenantId),
                "workspaces",jdbc.queryForList("""
                        SELECT w.id,w.name,EXISTS(SELECT 1 FROM workspace_member m WHERE m.workspace_id=w.id AND m.user_id=? AND m.role='OWNER' AND m.active=TRUE) AS canGrant
                        FROM workspace w WHERE w.tenant_id=? ORDER BY w.code
                        """,actor.id(),tenantId),
                "grants",jdbc.queryForList("SELECT workspace_id AS workspaceId,unit_id AS unitId,role FROM workspace_unit_grant WHERE tenant_id=?",tenantId),
                "audit",jdbc.queryForList("SELECT operation,resource_id AS resourceId,created_at AS createdAt FROM organization_audit WHERE tenant_id=? ORDER BY id DESC LIMIT 20",tenantId));
    }
    private static ApiException missing() { return new ApiException(404,"NOT_FOUND","组织资源不存在或不可访问"); }
    private static ApiException invalid() { return new ApiException(422,"INVALID_INPUT","请检查名称、类型、成员或授权角色"); }
    private static String name(String value) {
        if (value==null) throw invalid();
        String result=Normalizer.normalize(value.strip(),Normalizer.Form.NFC);
        if (result.isBlank() || result.length()>100 || result.chars().anyMatch(Character::isISOControl)) throw invalid();
        return result;
    }
    private void unit(String tenantId,String unitId) {
        if (jdbc.queryForList("SELECT id FROM organization_unit WHERE tenant_id=? AND id=?",String.class,tenantId,unitId).isEmpty()) throw missing();
    }
    private void audit(String tenantId,Actor actor,String id,String operation,Map<String,Object> detail) {
        jdbc.update("INSERT INTO organization_audit(tenant_id,actor_id,resource_id,operation,detail_json) VALUES (?,?,?,?,?)",tenantId,actor.id(),id,operation,json.writeValueAsString(detail));
    }
    @Transactional
    public Created createUnit(String username,String tenantId,NewUnit request) {
        var actor=admin(username,tenantId,true);
        if (request==null || request.kind()==null || !List.of("DEPARTMENT","TEAM").contains(request.kind())) throw invalid();
        String label=name(request.name());
        if (request.parentId()!=null && jdbc.queryForList("SELECT id FROM organization_unit WHERE tenant_id=? AND id=? AND kind='DEPARTMENT'",String.class,tenantId,request.parentId()).isEmpty()) throw missing();
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO organization_unit(id,tenant_id,name,kind,parent_id) VALUES (?,?,?,?,?)",id,tenantId,label,request.kind(),request.parentId());
        audit(tenantId,actor,id,"UNIT_CREATED",Map.of("kind",request.kind()));
        return new Created(id);
    }
    @Transactional
    public void setMembership(String username,String tenantId,String unitId,String userId,Membership request) {
        var actor=admin(username,tenantId,true); unit(tenantId,unitId);
        if (request==null || request.active()==null) throw invalid();
        var targets=jdbc.queryForList("SELECT m.active,u.enabled FROM tenant_member m JOIN user_account u ON u.id=m.user_id WHERE m.tenant_id=? AND m.user_id=?",tenantId,userId);
        if (targets.isEmpty() || (request.active() && (!Boolean.TRUE.equals(targets.getFirst().get("active")) || !Boolean.TRUE.equals(targets.getFirst().get("enabled"))))) throw missing();
        var previous=jdbc.queryForList("SELECT active FROM organization_unit_member WHERE tenant_id=? AND unit_id=? AND user_id=?",Boolean.class,tenantId,unitId,userId);
        if ((!previous.isEmpty() && previous.getFirst().equals(request.active())) || (previous.isEmpty() && !request.active())) return;
        jdbc.update("INSERT INTO organization_unit_member(tenant_id,unit_id,user_id,active) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE active=?",tenantId,unitId,userId,request.active(),request.active());
        audit(tenantId,actor,unitId,"UNIT_MEMBERSHIP_SET",Map.of("userId",userId,"active",request.active()));
    }
    @Transactional
    public Created createWorkspace(String username,String tenantId,NewWorkspace request) {
        var actor=admin(username,tenantId,true);
        if (request==null) throw invalid();
        String label=name(request.name()),id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO workspace(id,tenant_id,code,name) VALUES (?,?,?,?)",id,tenantId,"w-"+id.replace("-",""),label);
        jdbc.update("INSERT INTO workspace_member(workspace_id,user_id,role) VALUES (?,?,'OWNER')",id,actor.id());
        jdbc.update("INSERT INTO application(id,workspace_id,name) VALUES (?,?,?)",UUID.randomUUID().toString(),id,"技术文档提取");
        audit(tenantId,actor,id,"WORKSPACE_CREATED",Map.of("ownerId",actor.id()));
        return new Created(id);
    }
    @Transactional
    public void setGrant(String username,String tenantId,String workspaceId,String unitId,Grant request) {
        var actor=admin(username,tenantId,true); unit(tenantId,unitId);
        if (jdbc.queryForList("SELECT id FROM workspace WHERE id=? AND tenant_id=?",String.class,workspaceId,tenantId).isEmpty()) throw missing();
        if (!access.require(username,workspaceId,false,false,false).role().equals("OWNER")) throw new ApiException(403,"FORBIDDEN","授权还需要目标空间的OWNER权限");
        if (request==null || request.role()==null || !List.of("OPERATOR","VIEWER","NONE").contains(request.role())) throw invalid();
        var previous=jdbc.queryForList("SELECT role FROM workspace_unit_grant WHERE tenant_id=? AND workspace_id=? AND unit_id=?",String.class,tenantId,workspaceId,unitId);
        if ((!previous.isEmpty() && previous.getFirst().equals(request.role())) || (previous.isEmpty() && request.role().equals("NONE"))) return;
        if (request.role().equals("NONE")) jdbc.update("DELETE FROM workspace_unit_grant WHERE tenant_id=? AND workspace_id=? AND unit_id=?",tenantId,workspaceId,unitId);
        else jdbc.update("INSERT INTO workspace_unit_grant(tenant_id,workspace_id,unit_id,role) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE role=?",tenantId,workspaceId,unitId,request.role(),request.role());
        audit(tenantId,actor,workspaceId,"WORKSPACE_GRANT_SET",Map.of("unitId",unitId,"role",request.role()));
    }
}
