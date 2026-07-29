package it.itsprodigi.proofchain.custodycase.api;

import io.swagger.v3.oas.annotations.media.Schema;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import java.util.UUID;

@Schema(description = "Non-sensitive operator identity embedded in custody case responses.")
public record CaseOperatorSummaryResponse(
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                format = "uuid",
                example = "9a3b8bf4-1d96-4a1e-810e-5a2f8b6ee2b1")
        UUID id,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "case.manager")
        String username,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Jordan")
        String firstName,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Reed")
        String lastName,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "CASE_MANAGER")
        OperatorRole role,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "ACTIVE")
        OperatorStatus status) {}
