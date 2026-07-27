package it.itsprodigi.proofchain.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

class JwtTokenServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-27T08:00:00Z");
    private final SecretKey key = Keys.hmacShaKeyFor(new byte[32]);
    private final JwtTokenService service = new JwtTokenService(
            new JwtProperties(Base64.getEncoder().encodeToString(new byte[32]), Duration.ofMinutes(30)),
            key,
            Clock.fixed(NOW, java.time.ZoneOffset.UTC));

    @Test
    void roundTripAndMetadataAreDeterministic() {
        UUID id = UUID.randomUUID();
        IssuedAccessToken issued = service.issue(id, " Alice ", OperatorRole.ADMIN);
        JwtClaims claims = service.validate(issued.value());
        assertThat(claims.operatorId()).isEqualTo(id);
        assertThat(claims.username()).isEqualTo("alice");
        assertThat(issued.issuedAt()).isEqualTo(NOW);
        assertThat(issued.expiresAt()).isEqualTo(NOW.plusSeconds(1800));
        assertThat(issued.expiresInSeconds()).isEqualTo(1800);
        assertThat(claims.issuer()).isEqualTo("proofchain-api");
    }

    @Test
    void malformedAndExpiredTokensAreTranslated() {
        assertThatThrownBy(() -> service.validate("not-a-token")).isExactlyInstanceOf(InvalidJwtException.class);
        JwtTokenService shortService = new JwtTokenService(
                serviceProperties(Duration.ofSeconds(1)), key, Clock.fixed(NOW, java.time.ZoneOffset.UTC));
        String token = shortService
                .issue(UUID.randomUUID(), "user", OperatorRole.AUDITOR)
                .value();
        assertThatThrownBy(() -> new JwtTokenService(
                                serviceProperties(Duration.ofSeconds(1)),
                                key,
                                Clock.fixed(NOW.plusSeconds(2), java.time.ZoneOffset.UTC))
                        .validate(token))
                .isExactlyInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void claimsAreAllowlisted() {
        var claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(service.issue(UUID.randomUUID(), "user", OperatorRole.ADMIN)
                        .value())
                .getPayload();
        assertThat(claims.keySet()).containsExactlyInAnyOrder("sub", "username", "role", "iat", "exp", "jti", "iss");
    }

    private JwtProperties serviceProperties(Duration ttl) {
        return new JwtProperties("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", ttl);
    }
}
