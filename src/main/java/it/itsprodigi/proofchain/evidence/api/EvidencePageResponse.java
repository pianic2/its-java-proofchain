package it.itsprodigi.proofchain.evidence.api;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Deterministically ordered page of digital evidence visible in one custody case.")
public record EvidencePageResponse(
        @ArraySchema(
                arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED),
                schema = @Schema(implementation = EvidenceSummaryResponse.class))
        List<EvidenceSummaryResponse> content,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "0", minimum = "0")
        int page,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "20", minimum = "1", maximum = "100")
        int size,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2", minimum = "0")
        long totalElements,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "1", minimum = "0")
        int totalPages) {}
