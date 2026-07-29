package it.itsprodigi.proofchain.custodycase.api;

import io.swagger.v3.oas.annotations.media.Schema;
import it.itsprodigi.proofchain.custodycase.domain.CasePriority;
import it.itsprodigi.proofchain.custodycase.domain.CaseStatus;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Custody case representation without persistence locking metadata.")
public record CaseResponse(
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                format = "uuid",
                example = "1ca01c67-75b9-48e3-a2ed-72259373c67c")
        UUID id,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Mobile device seizure")
        String title,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                types = {"string", "null"},
                example = "Device collected under warrant 2026-0142.")
        String description,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                types = {"string", "null"},
                example = "Court of Rome")
        String authorityName,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                types = {"string", "null"},
                example = "WARRANT-2026-0142")
        String externalReference,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                types = {"string", "null"},
                example = "Evidence room A")
        String location,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "HIGH")
        CasePriority priority,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "OPEN")
        CaseStatus status,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, implementation = CaseOperatorSummaryResponse.class)
        CaseOperatorSummaryResponse createdBy,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                type = "string",
                format = "date-time",
                example = "2026-07-29T08:15:30.123456Z")
        Instant createdAt,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                type = "string",
                format = "date-time",
                example = "2026-07-29T08:15:30.123456Z")
        Instant updatedAt,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                types = {"string", "null"},
                format = "date-time",
                example = "2026-07-30T10:20:40.654321Z",
                description = "Closure time; null while the case is OPEN.")
        Instant closedAt) {}
