package it.itsprodigi.proofchain.evidence.api;

import io.swagger.v3.oas.annotations.media.Schema;
import it.itsprodigi.proofchain.evidence.domain.AcquisitionMethod;
import it.itsprodigi.proofchain.evidence.domain.EvidenceStatus;
import it.itsprodigi.proofchain.evidence.domain.SourceType;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Compact digital evidence metadata for deterministic case-scoped listings.")
public record EvidenceSummaryResponse(
        UUID id,
        UUID caseId,
        String referenceTag,
        String title,
        EvidenceStatus status,
        SourceType sourceType,
        AcquisitionMethod acquisitionMethod,
        String originalFilename,
        String fileExtension,
        String mediaType,
        long fileSize,
        String contentSha256,
        String contextualSha256,
        EvidenceOperatorSummaryResponse currentHolder,
        EvidenceOperatorSummaryResponse uploadedBy,
        Instant acquiredAt,
        Instant createdAt,
        Instant updatedAt) {}
