package it.itsprodigi.proofchain.evidence.application;

import it.itsprodigi.proofchain.evidence.api.EvidenceOperatorSummaryResponse;
import it.itsprodigi.proofchain.evidence.api.EvidenceResponse;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.operator.domain.Operator;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class EvidenceMapper {

    public EvidenceResponse toResponse(DigitalEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence must not be null");
        return new EvidenceResponse(
                evidence.getId(),
                evidence.getCustodyCase().getId(),
                evidence.getReferenceTag(),
                evidence.getTitle(),
                evidence.getDescription(),
                evidence.getStatus(),
                summary(evidence.getCurrentHolder()),
                summary(evidence.getUploadedBy()),
                evidence.getCreatedAt(),
                evidence.getUpdatedAt(),
                evidence.getSourceType(),
                evidence.getSourceDescription(),
                evidence.getSourceManufacturer(),
                evidence.getSourceModel(),
                evidence.getSourceSerialNumber(),
                evidence.getSourceLogicalIdentifier(),
                evidence.getAcquisitionMethod(),
                evidence.getAcquiredAt(),
                evidence.getAcquisitionLocation(),
                evidence.getAcquisitionToolName(),
                evidence.getAcquisitionToolVersion(),
                evidence.getAcquisitionNotes(),
                evidence.getOriginalFilename(),
                evidence.getFileExtension(),
                evidence.getMediaType(),
                evidence.getFileSize(),
                evidence.getContentSha256(),
                evidence.getContextualSha256());
    }

    private static EvidenceOperatorSummaryResponse summary(Operator operator) {
        if (operator == null) {
            return null;
        }
        return new EvidenceOperatorSummaryResponse(
                operator.getId(),
                operator.getUsername(),
                operator.getFirstName(),
                operator.getLastName(),
                operator.getRole(),
                operator.getStatus());
    }
}
