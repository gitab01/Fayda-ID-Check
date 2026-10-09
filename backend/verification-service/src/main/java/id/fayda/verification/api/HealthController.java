package id.fayda.verification.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated liveness for the orchestrator's probe (CONTRACT §1 lists no auth on it, and
 * {@code SecurityConfig} keeps {@code /api/v1/health} permitAll so a probe can never fail on a
 * token or a rate bucket). Readiness with the database and inference checks is
 * {@code /actuator/health/readiness}.
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    @GetMapping
    public Map<String, String> health() {
        return Map.of("service", "verification-service", "status", "ok");
    }
}
