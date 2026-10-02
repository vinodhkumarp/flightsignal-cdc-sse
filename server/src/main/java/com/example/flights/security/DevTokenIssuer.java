package com.example.flights.security;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import com.example.flights.api.ApiException;
import com.example.flights.config.SecurityProperties;

/** Issues development JWTs shaped like the organisation's SSO tokens. */
public class DevTokenIssuer {

    private final JwtEncoder encoder;
    private final SecurityProperties properties;

    DevTokenIssuer(JwtEncoder encoder, SecurityProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    public DevToken issue(String name, List<String> stations) {
        if (name == null || name.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "name is required.");
        }
        if (stations == null || stations.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Select at least one station.");
        }

        var normalized = stations.stream()
                .map(station -> station == null ? "" : station.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        for (var station : normalized) {
            var global = properties.allStationsValues().stream()
                    .anyMatch(value -> value.equalsIgnoreCase(station));
            if (!global && !StationScope.isStationCode(station)) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "stations must be three-letter IATA codes.");
            }
        }

        var now = Instant.now();
        var expiresAt = now.plus(properties.dev().tokenTtl());
        var trimmedName = name.trim();
        var claims = JwtClaimsSet.builder()
                .issuer(properties.dev().issuer())
                .subject(trimmedName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "."))
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim(properties.nameClaim(), trimmedName)
                .claim(properties.stationsClaim(), normalized)
                .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        var token = encoder.encode(JwtEncoderParameters.from(header, claims));
        return new DevToken(token.getTokenValue(), "Bearer", expiresAt);
    }

    public record DevToken(String accessToken, String tokenType, Instant expiresAt) {
    }
}
