package it.itsprodigi.proofchain.auth.application;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import it.itsprodigi.proofchain.auth.config.JwtConfig;
import it.itsprodigi.proofchain.operator.domain.OperatorNormalizer;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {
    private static final Set<String> ALLOWED = Set.of("sub", "username", "role", "iat", "exp", "jti", "iss");
    private final JwtProperties properties;
    private final SecretKey key;
    private final Clock clock;

    public JwtTokenService(JwtProperties properties, SecretKey key, Clock clock) {
        this.properties = properties;
        this.key = key;
        this.clock = clock;
    }

    public IssuedAccessToken issue(UUID operatorId, String username, OperatorRole role) {
        Instant issued = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant expires = issued.plus(properties.accessTokenTtl()).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        if (!expires.isAfter(issued)) throw new IllegalStateException("JWT expiration must be after issuance");
        String token = Jwts.builder()
                .subject(operatorId.toString())
                .claim("username", OperatorNormalizer.normalizeUsername(username))
                .claim("role", role.name())
                .issuedAt(Date.from(issued))
                .expiration(Date.from(expires))
                .id(UUID.randomUUID().toString())
                .issuer(JwtConfig.ISSUER)
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedAccessToken(token, issued, expires, expires.getEpochSecond() - issued.getEpochSecond());
    }

    public JwtClaims validate(String token) {
        try {
            var parsed = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .clockSkewSeconds(0)
                    .requireIssuer(JwtConfig.ISSUER)
                    .build()
                    .parseSignedClaims(token);
            if (!Jwts.SIG.HS256.getId().equals(parsed.getHeader().getAlgorithm())) throw new InvalidJwtException();
            Claims c = parsed.getPayload();
            if (!c.keySet().equals(ALLOWED)) throw new InvalidJwtException();
            UUID sub = UUID.fromString(c.getSubject());
            UUID jti = UUID.fromString(c.getId());
            if (jti.version() != 4) throw new InvalidJwtException();
            String username = c.get("username", String.class);
            if (!username.equals(OperatorNormalizer.normalizeUsername(username))) throw new InvalidJwtException();
            OperatorRole role = OperatorRole.valueOf(c.get("role", String.class));
            Instant iat = c.getIssuedAt().toInstant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            Instant exp = c.getExpiration().toInstant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            if (iat.isAfter(clock.instant()) || !exp.isAfter(iat)) throw new InvalidJwtException();
            return new JwtClaims(sub, username, role, iat, exp, jti, c.getIssuer());
        } catch (io.jsonwebtoken.ExpiredJwtException ex) {
            throw new ExpiredJwtException();
        } catch (InvalidJwtException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new InvalidJwtException();
        }
    }
}
