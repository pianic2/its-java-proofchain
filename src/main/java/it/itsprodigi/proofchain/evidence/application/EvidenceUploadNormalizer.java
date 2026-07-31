package it.itsprodigi.proofchain.evidence.application;

import it.itsprodigi.proofchain.evidence.api.CreateEvidenceRequest;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidenceNormalizer;
import java.time.Instant;
import java.util.List;
import org.springframework.http.MediaType;

final class EvidenceUploadNormalizer {

    private static final String REFERENCE_TAG_PATTERN = "[A-Z0-9][A-Z0-9._-]{0,63}";
    private static final String DEFAULT_MEDIA_TYPE = MediaType.APPLICATION_OCTET_STREAM_VALUE;

    private EvidenceUploadNormalizer() {}

    static void validateMetadata(CreateEvidenceRequest request) {
        if (request == null
                || request.title() == null
                || request.sourceType() == null
                || request.acquisitionMethod() == null
                || request.initialHolderId() == null) {
            throw new EvidenceRequestValidationException();
        }
        String title = DigitalEvidenceNormalizer.normalizeRequired(request.title(), "title");
        if (title.length() < 3 || title.length() > 200) {
            throw new EvidenceRequestValidationException();
        }
        String tag = DigitalEvidenceNormalizer.normalizeReferenceTag(request.referenceTag());
        if (tag != null && !tag.matches(REFERENCE_TAG_PATTERN)) {
            throw new EvidenceRequestValidationException();
        }
        for (BoundedValue value : List.of(
                new BoundedValue(request.description(), 2000),
                new BoundedValue(request.sourceDescription(), 500),
                new BoundedValue(request.sourceManufacturer(), 100),
                new BoundedValue(request.sourceModel(), 100),
                new BoundedValue(request.sourceSerialNumber(), 200),
                new BoundedValue(request.sourceLogicalIdentifier(), 300),
                new BoundedValue(request.acquisitionLocation(), 300),
                new BoundedValue(request.acquisitionToolName(), 200),
                new BoundedValue(request.acquisitionToolVersion(), 100),
                new BoundedValue(request.acquisitionNotes(), 2000))) {
            String normalized = DigitalEvidenceNormalizer.normalizeOptional(value.value());
            if (normalized != null && normalized.length() > value.maximumLength()) {
                throw new EvidenceRequestValidationException();
            }
        }
        if (request.acquiredAt() != null && request.acquiredAt().isAfter(Instant.now())) {
            throw new EvidenceRequestValidationException();
        }
    }

    static String filename(String suppliedFilename) {
        if (suppliedFilename == null) {
            throw new EvidenceRequestValidationException();
        }
        String candidate = suppliedFilename.strip();
        int separator = Math.max(candidate.lastIndexOf('/'), candidate.lastIndexOf('\\'));
        String filename = candidate.substring(separator + 1).strip();
        if (filename.isEmpty()
                || filename.equals(".")
                || filename.equals("..")
                || filename.length() > 255
                || filename.codePoints().anyMatch(Character::isISOControl)) {
            throw new EvidenceRequestValidationException();
        }
        return filename;
    }

    static String mediaType(String suppliedMediaType) {
        String candidate = DigitalEvidenceNormalizer.normalizeOptional(suppliedMediaType);
        if (candidate == null || candidate.length() > 255) {
            return DEFAULT_MEDIA_TYPE;
        }
        try {
            MediaType.parseMediaType(candidate);
            return candidate;
        } catch (IllegalArgumentException exception) {
            return DEFAULT_MEDIA_TYPE;
        }
    }

    private record BoundedValue(String value, int maximumLength) {}
}
