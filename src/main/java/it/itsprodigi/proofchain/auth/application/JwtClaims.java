package it.itsprodigi.proofchain.auth.application;

import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import java.time.Instant;
import java.util.UUID;

public record JwtClaims(
        UUID operatorId,
        String username,
        OperatorRole role,
        Instant issuedAt,
        Instant expiresAt,
        UUID tokenId,
        String issuer) {}
