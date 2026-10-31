package dev.agentops.model;

import java.time.Instant;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/model-usage")
@PreAuthorize("hasRole('OPS_ADMIN')")
public class ModelUsageController {
    private final ModelConfigurationService service;
    public ModelUsageController(ModelConfigurationService service) { this.service = service; }

    @GetMapping("/summary")
    ModelUsageStore.Summary summary(@RequestParam(required=false) String purpose,
                                    @RequestParam(required=false) Instant from,
                                    @RequestParam(required=false) Instant to) {
        return service.summary(purpose, from, to);
    }

    @GetMapping("/requests")
    Object requests(@RequestParam(required=false) String purpose,
                    @RequestParam(required=false) Instant from,
                    @RequestParam(required=false) Instant to,
                    @RequestParam(defaultValue="0") int page,
                    @RequestParam(defaultValue="20") int size) {
        return service.requests(purpose, from, to, page, size);
    }

    @GetMapping("/trend")
    Object trend(@RequestParam(required=false) String purpose,
                 @RequestParam(required=false) Instant from,
                 @RequestParam(required=false) Instant to) {
        return service.trend(purpose, from, to);
    }
}
