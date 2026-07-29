package it.itsprodigi.proofchain.evidence.api;

import io.swagger.v3.oas.annotations.media.Schema;
import it.itsprodigi.proofchain.evidence.domain.AcquisitionMethod;
import it.itsprodigi.proofchain.evidence.domain.EvidenceStatus;
import it.itsprodigi.proofchain.evidence.domain.SourceType;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Complete digital evidence metadata without internal storage or locking fields.")
public record EvidenceResponse(
        UUID id,
        UUID caseId,
        String referenceTag,
        String title,
        String description,
        EvidenceStatus status,
        EvidenceOperatorSummaryResponse currentHolder,
        EvidenceOperatorSummaryResponse uploadedBy,
        Instant createdAt,
        Instant updatedAt,
        SourceType sourceType,
        String sourceDescription,
        String sourceManufacturer,
        String sourceModel,
        String sourceSerialNumber,
        String sourceLogicalIdentifier,
        AcquisitionMethod acquisitionMethod,
        Instant acquiredAt,
        String acquisitionLocation,
        String acquisitionToolName,
        String acquisitionToolVersion,
        String acquisitionNotes,
        String originalFilename,
        String fileExtension,
        String mediaType,
        long fileSize,
        String contentSha256,
        String contextualSha256) {}
