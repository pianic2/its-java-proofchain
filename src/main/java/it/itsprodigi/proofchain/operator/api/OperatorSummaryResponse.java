package it.itsprodigi.proofchain.operator.api;

import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import java.util.UUID;

public record OperatorSummaryResponse(
        UUID id,
        String username,
        String email,
        String firstName,
        String lastName,
        OperatorRole role,
        OperatorStatus status) {}
