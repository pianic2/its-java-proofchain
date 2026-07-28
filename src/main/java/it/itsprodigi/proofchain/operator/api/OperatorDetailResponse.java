package it.itsprodigi.proofchain.operator.api;

import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import java.time.Instant;
import java.util.UUID;

public record OperatorDetailResponse(
        UUID id,
        String username,
        String email,
        String firstName,
        String lastName,
        OperatorRole role,
        OperatorStatus status,
        Instant createdAt,
        Instant updatedAt) {}
