package dev.relayops.job;

import java.net.URI;
import java.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class JobController {
    private final JobService jobs;
    public JobController(JobService jobs) { this.jobs = jobs; }

    @PostMapping("/jobs")
    ResponseEntity<?> create(Principal principal, @PathVariable String workspaceId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JobService.CreateJob request) {
        var accepted = jobs.create(principal.getName(), workspaceId, key, request);
        return ResponseEntity.accepted().location(URI.create("/api/v1/workspaces/" + workspaceId + "/jobs/" + accepted.jobId())).body(accepted);
    }

    @GetMapping("/jobs")
    Object list(Principal principal, @PathVariable String workspaceId) { return jobs.list(principal.getName(), workspaceId); }

    @PostMapping("/jobs/{jobId}/cancel")
    Object cancel(Principal principal, @PathVariable String workspaceId, @PathVariable String jobId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JobService.CancelJob request) {
        return jobs.cancel(principal.getName(), workspaceId, jobId, key, request);
    }

    @PostMapping("/jobs/{jobId}/retry")
    ResponseEntity<?> retry(Principal principal, @PathVariable String workspaceId, @PathVariable String jobId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JobService.RetryJob request) {
        var accepted = jobs.retry(principal.getName(), workspaceId, jobId, key, request);
        return ResponseEntity.accepted().location(URI.create("/api/v1/workspaces/" + workspaceId + "/jobs/" + accepted.jobId())).body(accepted);
    }

    @GetMapping("/model-profile")
    Object modelProfile(Principal principal, @PathVariable String workspaceId) { return jobs.modelProfile(principal.getName(), workspaceId); }

    @GetMapping("/jobs/{jobId}")
    Object get(Principal principal, @PathVariable String workspaceId, @PathVariable String jobId) { return jobs.get(principal.getName(), workspaceId, jobId); }

    @GetMapping("/jobs/{jobId}/result")
    ResponseEntity<?> result(Principal principal, @PathVariable String workspaceId, @PathVariable String jobId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(jobs.result(principal.getName(), workspaceId, jobId));
    }

    @GetMapping("/budget")
    Object budget(Principal principal, @PathVariable String workspaceId) { return jobs.budget(principal.getName(), workspaceId); }
}
