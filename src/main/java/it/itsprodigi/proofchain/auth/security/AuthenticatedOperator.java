package it.itsprodigi.proofchain.auth.security;

import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import java.time.Instant;
import java.util.UUID;

public record AuthenticatedOperator(
        UUID id,
        String username,
        String email,
        String firstName,
        String lastName,
        OperatorRole role,
        OperatorStatus status,
        Instant createdAt,
        Instant updatedAt) {}
