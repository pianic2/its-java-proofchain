package it.itsprodigi.proofchain.custodycase.api;

import it.itsprodigi.proofchain.custodycase.domain.CasePriority;
import it.itsprodigi.proofchain.custodycase.domain.CaseStatus;
import java.time.Instant;
import java.util.UUID;

public record CaseResponse(
        UUID id,
        String title,
        String description,
        String authorityName,
        String externalReference,
        String location,
        CasePriority priority,
        CaseStatus status,
        CaseOperatorSummaryResponse createdBy,
        Instant createdAt,
        Instant updatedAt,
        Instant closedAt) {}
