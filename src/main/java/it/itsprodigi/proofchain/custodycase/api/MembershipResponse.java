package it.itsprodigi.proofchain.custodycase.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Assignment of one operator to a custody case.")
public record MembershipResponse(
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                format = "uuid",
                example = "0ff41e0a-b2d3-48bb-a18c-5559ea748de8")
        UUID id,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                format = "uuid",
                example = "1ca01c67-75b9-48e3-a2ed-72259373c67c")
        UUID caseId,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, implementation = CaseOperatorSummaryResponse.class)
        CaseOperatorSummaryResponse operator,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, implementation = CaseOperatorSummaryResponse.class)
        CaseOperatorSummaryResponse assignedBy,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                type = "string",
                format = "date-time",
                example = "2026-07-29T08:15:30.123456Z")
        Instant assignedAt) {}
