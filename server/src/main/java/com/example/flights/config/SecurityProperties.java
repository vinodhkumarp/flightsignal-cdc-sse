package com.example.flights.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How the API reads station access from the caller's JWT ({@code app.security.*}).
 *
 * <p>JWT validation itself is configured with the standard Spring Boot
 * properties, typically
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} pointing at the
 * organisation's SSO (Entra ID, Okta, Keycloak, Ping, ...).
 *
 * @param stationsClaim     claim holding the user's stations. A dotted path
 *                          ({@code ext.stations}) reads nested claims. The value
 *                          may be a JSON array or a comma/space separated string.
 * @param allStationsValues claim values that grant every station (head office,
 *                          network operations control)
 * @param nameClaim         claim used as the display name (falls back to
 *                          {@code preferred_username}, then {@code sub})
 * @param dev               local development token issuer
 */
@ConfigurationProperties("app.security")
public record SecurityProperties(
        @DefaultValue("stations") String stationsClaim,
        @DefaultValue({"*", "ALL"}) List<String> allStationsValues,
        @DefaultValue("name") String nameClaim,
        @DefaultValue Dev dev) {

    /**
     * Development-only HS256 token issuer used when no SSO is available
     * (local runs, demos, integration tests). Never enable in production.
     *
     * @param enabled  exposes {@code POST /api/dev/token} and trusts tokens
     *                 signed with {@code secret}
     * @param secret   HMAC secret, at least 32 bytes
     * @param issuer   {@code iss} claim of issued tokens (also validated)
     * @param tokenTtl lifetime of issued tokens
     */
    public record Dev(
            @DefaultValue("false") boolean enabled,
            String secret,
            @DefaultValue("flightsignal-dev") String issuer,
            @DefaultValue("8h") Duration tokenTtl) {
    }
}
