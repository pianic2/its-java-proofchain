package it.itsprodigi.proofchain.custodycase.api;

import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import java.util.UUID;

public record CaseOperatorSummaryResponse(
        UUID id, String username, String firstName, String lastName, OperatorRole role, OperatorStatus status) {}
