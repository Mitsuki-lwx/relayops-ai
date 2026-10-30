package dev.relayops.delivery;

import java.security.Principal;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class DeliveryController {
    private final DestinationService destinations;
    private final DeliveryStore deliveries;
    public DeliveryController(DestinationService destinations,DeliveryStore deliveries) { this.destinations=destinations;this.deliveries=deliveries; }
    @GetMapping("/destinations") Object list(Principal p,@PathVariable String workspaceId) { return destinations.list(p.getName(),workspaceId); }
    @GetMapping("/destination-catalog") Object catalog(Principal p,@PathVariable String workspaceId) { destinations.list(p.getName(),workspaceId); return destinations.catalog(); }
    @PostMapping("/destinations") ResponseEntity<?> create(Principal p,@PathVariable String workspaceId,@RequestBody DestinationService.Create body) { return ResponseEntity.status(201).body(destinations.create(p.getName(),workspaceId,body)); }
    @PutMapping("/destinations/{id}") Object enable(Principal p,@PathVariable String workspaceId,@PathVariable String id,@RequestBody DestinationService.Enable body) { destinations.enable(p.getName(),workspaceId,id,body);return Map.of("updated",true); }
    public record Reveal(Boolean acknowledgeSecretRead) {}
    @PostMapping("/destinations/{id}/reveal-key") ResponseEntity<?> reveal(Principal p,@PathVariable String workspaceId,@PathVariable String id,@RequestBody Reveal body) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(destinations.reveal(p.getName(),workspaceId,id,body!=null && Boolean.TRUE.equals(body.acknowledgeSecretRead())));
    }
    @GetMapping("/jobs/{jobId}/deliveries") Object deliveries(Principal p,@PathVariable String workspaceId,@PathVariable String jobId) {return deliveries.list(p.getName(),workspaceId,jobId);}
}
