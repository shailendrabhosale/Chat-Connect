package dev.chatconnect.auth.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated liveness-through-the-edge check: proves gateway → service routing, and is used by
 * smoke tests (P6.C3). Not a health probe: probes stay on /actuator and are never routed publicly.
 */
@RestController
@RequestMapping("/api/auth/v1")
class PingController {

    record Ping(String service, String status) {
    }

    @GetMapping("/ping")
    Ping ping() {
        return new Ping("auth-service", "ok");
    }
}
