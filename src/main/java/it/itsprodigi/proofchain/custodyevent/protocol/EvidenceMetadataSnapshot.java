package it.itsprodigi.proofchain.custodyevent.protocol;

import it.itsprodigi.proofchain.evidence.domain.AcquisitionMethod;
import it.itsprodigi.proofchain.evidence.domain.SourceType;
import java.time.Instant;
import java.util.Objects;

public record EvidenceMetadataSnapshot(
        String title,
        String description,
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
        String acquisitionNotes) {

    public EvidenceMetadataSnapshot {
        title = ProtocolValidation.requiredText(title, 3, 200, "title");
        description = ProtocolValidation.optionalText(description, 2000, "description");
        sourceType = Objects.requireNonNull(sourceType, "sourceType must not be null");
        sourceDescription = ProtocolValidation.optionalText(sourceDescription, 500, "sourceDescription");
        sourceManufacturer = ProtocolValidation.optionalText(sourceManufacturer, 100, "sourceManufacturer");
        sourceModel = ProtocolValidation.optionalText(sourceModel, 100, "sourceModel");
        sourceSerialNumber = ProtocolValidation.optionalText(sourceSerialNumber, 200, "sourceSerialNumber");
        sourceLogicalIdentifier =
                ProtocolValidation.optionalText(sourceLogicalIdentifier, 300, "sourceLogicalIdentifier");
        acquisitionMethod = Objects.requireNonNull(acquisitionMethod, "acquisitionMethod must not be null");
        if (acquiredAt != null) {
            acquiredAt = ProtocolValidation.microsecondInstant(acquiredAt, "acquiredAt");
        }
        acquisitionLocation = ProtocolValidation.optionalText(acquisitionLocation, 300, "acquisitionLocation");
        acquisitionToolName = ProtocolValidation.optionalText(acquisitionToolName, 200, "acquisitionToolName");
        acquisitionToolVersion = ProtocolValidation.optionalText(acquisitionToolVersion, 100, "acquisitionToolVersion");
        acquisitionNotes = ProtocolValidation.optionalText(acquisitionNotes, 2000, "acquisitionNotes");
    }
}
