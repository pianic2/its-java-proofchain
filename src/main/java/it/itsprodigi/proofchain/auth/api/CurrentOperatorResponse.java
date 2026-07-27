package it.itsprodigi.proofchain.auth.api;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import java.time.Instant;
import java.util.UUID;

public record CurrentOperatorResponse(
        UUID id,
        String username,
        String email,
        String firstName,
        String lastName,
        OperatorRole role,
        OperatorStatus status,
        Instant createdAt,
        Instant updatedAt) {
    public static CurrentOperatorResponse from(AuthenticatedOperator operator) {
        return new CurrentOperatorResponse(
                operator.id(),
                operator.username(),
                operator.email(),
                operator.firstName(),
                operator.lastName(),
                operator.role(),
                operator.status(),
                operator.createdAt(),
                operator.updatedAt());
    }
}
