package it.itsprodigi.proofchain.evidence.api;

import io.swagger.v3.oas.annotations.media.Schema;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import java.util.UUID;

@Schema(description = "Non-sensitive current operator identity embedded in evidence responses.")
public record EvidenceOperatorSummaryResponse(
        UUID id, String username, String firstName, String lastName, OperatorRole role, OperatorStatus status) {}
