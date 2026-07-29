package it.itsprodigi.proofchain.custodyevent.protocol;

import it.itsprodigi.proofchain.custodyevent.domain.EventType;
import it.itsprodigi.proofchain.evidence.domain.AcquisitionMethod;
import it.itsprodigi.proofchain.evidence.domain.EvidenceStatus;
import it.itsprodigi.proofchain.evidence.domain.SourceType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record EvidenceRegisteredPayload(
        boolean backfilled,
        String referenceTag,
        String title,
        String description,
        EvidenceStatus status,
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
        String contextualSha256,
        UUID uploadedById,
        UUID initialHolderId)
        implements CustodyEventPayload {

    public EvidenceRegisteredPayload {
        referenceTag = ProtocolValidation.referenceTag(referenceTag);
        EvidenceMetadataSnapshot metadata = new EvidenceMetadataSnapshot(
                title,
                description,
                sourceType,
                sourceDescription,
                sourceManufacturer,
                sourceModel,
                sourceSerialNumber,
                sourceLogicalIdentifier,
                acquisitionMethod,
                acquiredAt,
                acquisitionLocation,
                acquisitionToolName,
                acquisitionToolVersion,
                acquisitionNotes);
        title = metadata.title();
        description = metadata.description();
        sourceType = metadata.sourceType();
        sourceDescription = metadata.sourceDescription();
        sourceManufacturer = metadata.sourceManufacturer();
        sourceModel = metadata.sourceModel();
        sourceSerialNumber = metadata.sourceSerialNumber();
        sourceLogicalIdentifier = metadata.sourceLogicalIdentifier();
        acquisitionMethod = metadata.acquisitionMethod();
        acquiredAt = metadata.acquiredAt();
        acquisitionLocation = metadata.acquisitionLocation();
        acquisitionToolName = metadata.acquisitionToolName();
        acquisitionToolVersion = metadata.acquisitionToolVersion();
        acquisitionNotes = metadata.acquisitionNotes();
        status = Objects.requireNonNull(status, "status must not be null");
        originalFilename = ProtocolValidation.requiredText(originalFilename, 1, 255, "originalFilename");
        fileExtension = ProtocolValidation.fileExtension(fileExtension);
        mediaType = ProtocolValidation.requiredText(mediaType, 1, 255, "mediaType");
        fileSize = ProtocolValidation.positive(fileSize, "fileSize");
        contentSha256 = ProtocolValidation.sha256(contentSha256, "contentSha256");
        contextualSha256 = ProtocolValidation.sha256(contextualSha256, "contextualSha256");
        uploadedById = Objects.requireNonNull(uploadedById, "uploadedById must not be null");
        initialHolderId = Objects.requireNonNull(initialHolderId, "initialHolderId must not be null");
    }

    @Override
    public EventType eventType() {
        return EventType.EVIDENCE_REGISTERED;
    }
}
