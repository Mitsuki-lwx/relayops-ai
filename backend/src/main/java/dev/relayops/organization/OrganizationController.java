package dev.relayops.organization;

import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationController {
    private final OrganizationService service;
    public OrganizationController(OrganizationService service) { this.service=service; }
    @GetMapping Object list(Principal p) { return service.list(p.getName()); }
    @GetMapping("/{tenantId}") Object details(Principal p,@PathVariable String tenantId) { return service.details(p.getName(),tenantId); }
    @GetMapping("/{tenantId}/audit-events")
    Object auditEvents(Principal p,@PathVariable String tenantId,@RequestParam(required=false) String operation,
            @RequestParam(required=false) String resourceId,@RequestParam(required=false) String beforeId,
            @RequestParam(defaultValue="25") int limit) {
        return service.auditEvents(p.getName(),tenantId,operation,resourceId,beforeId,limit);
    }
    @PostMapping("/{tenantId}/units") @ResponseStatus(HttpStatus.CREATED)
    Object createUnit(Principal p,@PathVariable String tenantId,@RequestBody OrganizationService.NewUnit request) { return service.createUnit(p.getName(),tenantId,request); }
    @PostMapping("/{tenantId}/workspaces") @ResponseStatus(HttpStatus.CREATED)
    Object createWorkspace(Principal p,@PathVariable String tenantId,@RequestBody OrganizationService.NewWorkspace request) { return service.createWorkspace(p.getName(),tenantId,request); }
    @PutMapping("/{tenantId}/units/{unitId}/members/{userId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void membership(Principal p,@PathVariable String tenantId,@PathVariable String unitId,@PathVariable String userId,@RequestBody OrganizationService.Membership request) { service.setMembership(p.getName(),tenantId,unitId,userId,request); }
    @PutMapping("/{tenantId}/workspaces/{workspaceId}/grants/{unitId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void grant(Principal p,@PathVariable String tenantId,@PathVariable String workspaceId,@PathVariable String unitId,@RequestBody OrganizationService.Grant request) { service.setGrant(p.getName(),tenantId,workspaceId,unitId,request); }
}
