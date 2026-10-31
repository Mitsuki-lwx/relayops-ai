package dev.relayops.operations;

import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class OperationsController {
    private final OperationsService operations;
    public OperationsController(OperationsService operations) {this.operations=operations;}
    @GetMapping("/operations") Object overview(Principal p,@PathVariable String workspaceId) {
        return operations.overview(p.getName(),workspaceId);
    }
    @GetMapping("/audit-events") Object audit(Principal p,@PathVariable String workspaceId,
            @RequestParam(required=false) String operation,@RequestParam(required=false) String resourceId,
            @RequestParam(required=false) String beforeId,@RequestParam(defaultValue="25") int limit) {
        return operations.audit(p.getName(),workspaceId,operation,resourceId,beforeId,limit);
    }
}
