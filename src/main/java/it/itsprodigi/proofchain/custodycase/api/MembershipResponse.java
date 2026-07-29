package it.itsprodigi.proofchain.custodycase.api;

import java.time.Instant;
import java.util.UUID;

public record MembershipResponse(
        UUID id,
        UUID caseId,
        CaseOperatorSummaryResponse operator,
        CaseOperatorSummaryResponse assignedBy,
        Instant assignedAt) {}
