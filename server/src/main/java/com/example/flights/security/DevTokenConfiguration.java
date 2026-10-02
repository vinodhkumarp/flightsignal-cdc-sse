package com.example.flights.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.example.flights.config.SecurityProperties;

/**
 * Local development identity provider: trusts HS256 tokens signed with
 * {@code app.security.dev.secret} and issues them from
 * {@code POST /api/dev/token}. Lets the station-scoped UI be demoed and
 * tested without an SSO tenant. Must stay disabled in real environments,
 * where {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} points
 * at the organisation's SSO instead.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.security.dev", name = "enabled", havingValue = "true")
public class DevTokenConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(DevTokenConfiguration.class);
    private static final int MIN_SECRET_BYTES = 32;

    @Bean
    SecretKey devTokenSigningKey(SecurityProperties properties) {
        var secret = properties.dev().secret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "app.security.dev.secret must be at least " + MIN_SECRET_BYTES + " bytes");
        }
        LOGGER.warn("DEVELOPMENT TOKENS ENABLED: POST /api/dev/token issues JWTs for any "
                + "station. Never enable app.security.dev in a shared environment.");
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtDecoder devJwtDecoder(SecretKey devTokenSigningKey, SecurityProperties properties) {
        var decoder = NimbusJwtDecoder.withSecretKey(devTokenSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.dev().issuer()));
        return decoder;
    }

    @Bean
    DevTokenIssuer devTokenIssuer(SecretKey devTokenSigningKey, SecurityProperties properties) {
        var encoder = NimbusJwtEncoder.withSecretKey(devTokenSigningKey)
                .algorithm(MacAlgorithm.HS256)
                .build();
        return new DevTokenIssuer(encoder, properties);
    }
}
