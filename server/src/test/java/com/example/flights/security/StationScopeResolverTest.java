package com.example.flights.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import com.example.flights.config.SecurityProperties;

class StationScopeResolverTest {

    private final StationScopeResolver resolver = resolver("stations");

    @Test
    void readsAnArrayClaim() {
        var scope = resolver.resolve(jwt(Map.of("stations", List.of("syd", "SIN"))));

        assertThat(scope.all()).isFalse();
        assertThat(scope.stations()).containsExactly("SIN", "SYD");
    }

    @Test
    void readsASpaceOrCommaSeparatedClaim() {
        assertThat(resolver.resolve(jwt(Map.of("stations", "SYD SIN"))).stations())
                .containsExactly("SIN", "SYD");
        assertThat(resolver.resolve(jwt(Map.of("stations", "SYD,LHR"))).stations())
                .containsExactly("LHR", "SYD");
    }

    @Test
    void grantsEveryStationForAGlobalValue() {
        assertThat(resolver.resolve(jwt(Map.of("stations", List.of("*")))).all()).isTrue();
        assertThat(resolver.resolve(jwt(Map.of("stations", "all"))).all()).isTrue();
    }

    @Test
    void readsANestedClaimPath() {
        var nested = resolver("ext.location.stations");

        var scope = nested.resolve(jwt(Map.of("ext", Map.of("location", Map.of("stations", List.of("AKL"))))));

        assertThat(scope.stations()).containsExactly("AKL");
    }

    @Test
    void deniesByDefaultWhenTheClaimIsMissingOrMalformed() {
        assertThat(resolver.resolve(jwt(Map.of("roles", List.of("ops")))).isEmpty()).isTrue();
        assertThat(resolver.resolve(jwt(Map.of("stations", List.of("SYDNEY", "1")))).isEmpty()).isTrue();
        assertThat(resolver.resolve(null).isEmpty()).isTrue();
    }

    @Test
    void picksTheDisplayName() {
        assertThat(resolver.displayName(jwt(Map.of("name", "Priya Sharma")))).isEqualTo("Priya Sharma");
        assertThat(resolver.displayName(jwt(Map.of("preferred_username", "priya@example.test"))))
                .isEqualTo("priya@example.test");
    }

    private static StationScopeResolver resolver(String claim) {
        return new StationScopeResolver(new SecurityProperties(
                claim,
                List.of("*", "ALL"),
                "name",
                new SecurityProperties.Dev(false, null, "flightsignal-dev", Duration.ofHours(8))));
    }

    private static Jwt jwt(Map<String, Object> claims) {
        var builder = Jwt.withTokenValue("token").header("alg", "none").subject("user-1");
        claims.forEach(builder::claim);
        return builder.build();
    }
}
