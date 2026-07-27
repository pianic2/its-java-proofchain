package it.itsprodigi.proofchain.auth.config;

import io.jsonwebtoken.security.Keys;
import it.itsprodigi.proofchain.auth.application.JwtProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JwtConfig {
    public static final String ISSUER = "proofchain-api";

    @Bean
    JwtProperties jwtProperties(
            @Value("${proofchain.jwt.secret:}") String secret,
            @Value("${proofchain.jwt.access-token-ttl:PT30M}") Duration ttl) {
        if (secret == null || secret.isBlank()) throw new IllegalStateException("JWT secret is required");
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("JWT secret must be standard Base64", ex);
        }
        if (decoded.length < 32) throw new IllegalStateException("JWT secret must decode to at least 32 bytes");
        if (ttl.isZero() || ttl.isNegative()) throw new IllegalStateException("JWT access-token TTL must be positive");
        return new JwtProperties(secret, ttl);
    }

    @Bean
    SecretKey jwtSigningKey(JwtProperties properties) {
        return Keys.hmacShaKeyFor(Base64.getDecoder().decode(properties.secret()));
    }

    @Bean
    Clock jwtClock() {
        return Clock.systemUTC();
    }
}
