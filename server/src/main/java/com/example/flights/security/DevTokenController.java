package com.example.flights.security;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/dev/token}: development stand-in for the SSO login.
 * Only registered when {@code app.security.dev.enabled=true}.
 */
@RestController
@ConditionalOnProperty(prefix = "app.security.dev", name = "enabled", havingValue = "true")
@RequestMapping("/api/dev/token")
public class DevTokenController {

    private final DevTokenIssuer issuer;

    DevTokenController(DevTokenIssuer issuer) {
        this.issuer = issuer;
    }

    @PostMapping
    DevTokenIssuer.DevToken issue(@RequestBody DevTokenRequest request) {
        return issuer.issue(request.name(), request.stations());
    }

    record DevTokenRequest(String name, List<String> stations) {
    }
}
