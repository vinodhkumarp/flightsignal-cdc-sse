package com.example.flights.security;

import java.time.Instant;
import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/me}: who the caller is and which stations they cover. */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final StationScopeResolver resolver;

    public MeController(StationScopeResolver resolver) {
        this.resolver = resolver;
    }

    @GetMapping
    Me me(@AuthenticationPrincipal Jwt jwt) {
        var scope = resolver.resolve(jwt);
        return new Me(
                jwt.getSubject(),
                resolver.displayName(jwt),
                scope.all(),
                List.copyOf(scope.stations()),
                jwt.getExpiresAt());
    }

    record Me(
            String subject,
            String name,
            boolean allStations,
            List<String> stations,
            Instant tokenExpiresAt) {
    }
}
