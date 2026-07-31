package it.itsprodigi.proofchain.custodycase.api;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Deterministically ordered page of custody cases accessible to the caller.")
public record CasePageResponse(
        @ArraySchema(
                arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED),
                schema = @Schema(implementation = CaseResponse.class))
        List<CaseResponse> content,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "0", minimum = "0")
        int page,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "20", minimum = "1", maximum = "100")
        int size,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "1", minimum = "0")
        long totalElements,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "1", minimum = "0")
        int totalPages) {}
