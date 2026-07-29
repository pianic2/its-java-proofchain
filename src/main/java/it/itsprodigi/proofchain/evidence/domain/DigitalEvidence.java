package it.itsprodigi.proofchain.evidence.domain;

import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.operator.domain.Operator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "digital_evidence")
public class DigitalEvidence {

    private static final String REFERENCE_TAG_PATTERN = "[A-Z0-9][A-Z0-9._-]{0,63}";
    private static final String SHA_256_PATTERN = "[0-9a-f]{64}";
    private static final String DEFAULT_MEDIA_TYPE = "application/octet-stream";
    private static final String ZERO_CUSTODY_HASH = "0".repeat(64);

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "case_id", nullable = false, updatable = false)
    private CustodyCase custodyCase;

    @Size(max = 64)
    @Pattern(regexp = REFERENCE_TAG_PATTERN)
    @Column(name = "reference_tag", length = 64, updatable = false)
    private String referenceTag;

    @NotBlank
    @Size(min = 3, max = 200)
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Size(max = 2000)
    @Column(name = "description", length = 2000)
    private String description;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private EvidenceStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_holder_operator_id")
    private Operator currentHolder;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by_operator_id", nullable = false, updatable = false)
    private Operator uploadedBy;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    private SourceType sourceType;

    @Size(max = 500)
    @Column(name = "source_description", length = 500)
    private String sourceDescription;

    @Size(max = 100)
    @Column(name = "source_manufacturer", length = 100)
    private String sourceManufacturer;

    @Size(max = 100)
    @Column(name = "source_model", length = 100)
    private String sourceModel;

    @Size(max = 200)
    @Column(name = "source_serial_number", length = 200)
    private String sourceSerialNumber;

    @Size(max = 300)
    @Column(name = "source_logical_identifier", length = 300)
    private String sourceLogicalIdentifier;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "acquisition_method", nullable = false, length = 32)
    private AcquisitionMethod acquisitionMethod;

    @Size(max = 300)
    @Column(name = "acquisition_location", length = 300)
    private String acquisitionLocation;

    @Size(max = 200)
    @Column(name = "acquisition_tool_name", length = 200)
    private String acquisitionToolName;

    @Size(max = 100)
    @Column(name = "acquisition_tool_version", length = 100)
    private String acquisitionToolVersion;

    @Size(max = 2000)
    @Column(name = "acquisition_notes", length = 2000)
    private String acquisitionNotes;

    @Column(name = "acquired_at")
    private Instant acquiredAt;

    @NotBlank
    @Size(max = 255)
    @Column(name = "original_filename", nullable = false, length = 255, updatable = false)
    private String originalFilename;

    @Size(max = 32)
    @Column(name = "file_extension", length = 32, updatable = false)
    private String fileExtension;

    @NotBlank
    @Size(max = 255)
    @Column(name = "media_type", nullable = false, length = 255, updatable = false)
    private String mediaType;

    @Positive
    @Column(name = "file_size", nullable = false, updatable = false)
    private long fileSize;

    @NotBlank
    @Size(min = 64, max = 64)
    @Pattern(regexp = SHA_256_PATTERN)
    @Column(name = "content_sha256", nullable = false, length = 64, updatable = false)
    private String contentSha256;

    @NotBlank
    @Size(min = 64, max = 64)
    @Pattern(regexp = SHA_256_PATTERN)
    @Column(name = "contextual_sha256", nullable = false, length = 64, updatable = false)
    private String contextualSha256;

    @NotBlank
    @Size(max = 500)
    @Column(name = "storage_key", nullable = false, length = 500, updatable = false)
    private String storageKey;

    @PositiveOrZero
    @Column(name = "custody_event_count", nullable = false)
    private long custodyEventCount;

    @NotBlank
    @Size(min = 64, max = 64)
    @Pattern(regexp = SHA_256_PATTERN)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "custody_chain_head_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String custodyChainHeadHash;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @NotNull
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @PositiveOrZero
    @Column(name = "version", nullable = false)
    private long version;

    protected DigitalEvidence() {}

    private DigitalEvidence(
            UUID id,
            CustodyCase custodyCase,
            Operator currentHolder,
            Operator uploadedBy,
            String referenceTag,
            String title,
            String description,
            SourceType sourceType,
            String sourceDescription,
            String sourceManufacturer,
            String sourceModel,
            String sourceSerialNumber,
            String sourceLogicalIdentifier,
            AcquisitionMethod acquisitionMethod,
            String acquisitionLocation,
            String acquisitionToolName,
            String acquisitionToolVersion,
            String acquisitionNotes,
            Instant acquiredAt,
            String originalFilename,
            String mediaType,
            long fileSize,
            String contentSha256,
            String contextualSha256,
            String storageKey,
            Instant registeredAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.custodyCase = Objects.requireNonNull(custodyCase, "custodyCase must not be null");
        this.currentHolder = Objects.requireNonNull(currentHolder, "currentHolder must not be null");
        this.uploadedBy = Objects.requireNonNull(uploadedBy, "uploadedBy must not be null");
        this.referenceTag = validReferenceTag(referenceTag);
        this.title = validTitle(title);
        this.description = validOptional(description, 2000, "description");
        this.sourceType = Objects.requireNonNull(sourceType, "sourceType must not be null");
        this.sourceDescription = validOptional(sourceDescription, 500, "sourceDescription");
        this.sourceManufacturer = validOptional(sourceManufacturer, 100, "sourceManufacturer");
        this.sourceModel = validOptional(sourceModel, 100, "sourceModel");
        this.sourceSerialNumber = validOptional(sourceSerialNumber, 200, "sourceSerialNumber");
        this.sourceLogicalIdentifier = validOptional(sourceLogicalIdentifier, 300, "sourceLogicalIdentifier");
        this.acquisitionMethod = Objects.requireNonNull(acquisitionMethod, "acquisitionMethod must not be null");
        this.acquisitionLocation = validOptional(acquisitionLocation, 300, "acquisitionLocation");
        this.acquisitionToolName = validOptional(acquisitionToolName, 200, "acquisitionToolName");
        this.acquisitionToolVersion = validOptional(acquisitionToolVersion, 100, "acquisitionToolVersion");
        this.acquisitionNotes = validOptional(acquisitionNotes, 2000, "acquisitionNotes");
        this.originalFilename = validOriginalFilename(originalFilename);
        fileExtension = deriveExtension(this.originalFilename);
        this.mediaType = validMediaType(mediaType);
        this.fileSize = validFileSize(fileSize);
        this.contentSha256 = validSha256(contentSha256, "contentSha256");
        this.contextualSha256 = validSha256(contextualSha256, "contextualSha256");
        this.storageKey = validStorageKey(storageKey);
        custodyEventCount = 0L;
        custodyChainHeadHash = ZERO_CUSTODY_HASH;
        status = EvidenceStatus.IN_CUSTODY;
        Instant registrationTime = validRegistrationTime(registeredAt);
        createdAt = registrationTime;
        updatedAt = registrationTime;
        this.acquiredAt = validAcquiredAt(acquiredAt);
        version = 0L;
    }

    public static DigitalEvidence create(
            CustodyCase custodyCase,
            Operator currentHolder,
            Operator uploadedBy,
            String referenceTag,
            String title,
            String description,
            SourceType sourceType,
            String sourceDescription,
            String sourceManufacturer,
            String sourceModel,
            String sourceSerialNumber,
            String sourceLogicalIdentifier,
            AcquisitionMethod acquisitionMethod,
            String acquisitionLocation,
            String acquisitionToolName,
            String acquisitionToolVersion,
            String acquisitionNotes,
            Instant acquiredAt,
            String originalFilename,
            String mediaType,
            long fileSize,
            String contentSha256,
            String contextualSha256,
            String storageKey) {
        return create(
                UUID.randomUUID(),
                custodyCase,
                currentHolder,
                uploadedBy,
                referenceTag,
                title,
                description,
                sourceType,
                sourceDescription,
                sourceManufacturer,
                sourceModel,
                sourceSerialNumber,
                sourceLogicalIdentifier,
                acquisitionMethod,
                acquisitionLocation,
                acquisitionToolName,
                acquisitionToolVersion,
                acquisitionNotes,
                acquiredAt,
                originalFilename,
                mediaType,
                fileSize,
                contentSha256,
                contextualSha256,
                storageKey,
                nowAtMicrosecondPrecision());
    }

    public static DigitalEvidence create(
            UUID id,
            CustodyCase custodyCase,
            Operator currentHolder,
            Operator uploadedBy,
            String referenceTag,
            String title,
            String description,
            SourceType sourceType,
            String sourceDescription,
            String sourceManufacturer,
            String sourceModel,
            String sourceSerialNumber,
            String sourceLogicalIdentifier,
            AcquisitionMethod acquisitionMethod,
            String acquisitionLocation,
            String acquisitionToolName,
            String acquisitionToolVersion,
            String acquisitionNotes,
            Instant acquiredAt,
            String originalFilename,
            String mediaType,
            long fileSize,
            String contentSha256,
            String contextualSha256,
            String storageKey) {
        return new DigitalEvidence(
                id,
                custodyCase,
                currentHolder,
                uploadedBy,
                referenceTag,
                title,
                description,
                sourceType,
                sourceDescription,
                sourceManufacturer,
                sourceModel,
                sourceSerialNumber,
                sourceLogicalIdentifier,
                acquisitionMethod,
                acquisitionLocation,
                acquisitionToolName,
                acquisitionToolVersion,
                acquisitionNotes,
                acquiredAt,
                originalFilename,
                mediaType,
                fileSize,
                contentSha256,
                contextualSha256,
                storageKey,
                nowAtMicrosecondPrecision());
    }

    public static DigitalEvidence create(
            UUID id,
            CustodyCase custodyCase,
            Operator currentHolder,
            Operator uploadedBy,
            String referenceTag,
            String title,
            String description,
            SourceType sourceType,
            String sourceDescription,
            String sourceManufacturer,
            String sourceModel,
            String sourceSerialNumber,
            String sourceLogicalIdentifier,
            AcquisitionMethod acquisitionMethod,
            String acquisitionLocation,
            String acquisitionToolName,
            String acquisitionToolVersion,
            String acquisitionNotes,
            Instant acquiredAt,
            String originalFilename,
            String mediaType,
            long fileSize,
            String contentSha256,
            String contextualSha256,
            String storageKey,
            Instant registeredAt) {
        return new DigitalEvidence(
                id,
                custodyCase,
                currentHolder,
                uploadedBy,
                referenceTag,
                title,
                description,
                sourceType,
                sourceDescription,
                sourceManufacturer,
                sourceModel,
                sourceSerialNumber,
                sourceLogicalIdentifier,
                acquisitionMethod,
                acquisitionLocation,
                acquisitionToolName,
                acquisitionToolVersion,
                acquisitionNotes,
                acquiredAt,
                originalFilename,
                mediaType,
                fileSize,
                contentSha256,
                contextualSha256,
                storageKey,
                registeredAt);
    }

    public void updateMetadata(String title, String description) {
        requireDescriptiveMetadataMutable();
        String newTitle = validTitle(title);
        String newDescription = validOptional(description, 2000, "description");
        this.title = newTitle;
        this.description = newDescription;
        touch();
    }

    public void updateSourceMetadata(
            SourceType sourceType,
            String sourceDescription,
            String sourceManufacturer,
            String sourceModel,
            String sourceSerialNumber,
            String sourceLogicalIdentifier) {
        requireDescriptiveMetadataMutable();
        SourceType newSourceType = Objects.requireNonNull(sourceType, "sourceType must not be null");
        String newDescription = validOptional(sourceDescription, 500, "sourceDescription");
        String newManufacturer = validOptional(sourceManufacturer, 100, "sourceManufacturer");
        String newModel = validOptional(sourceModel, 100, "sourceModel");
        String newSerialNumber = validOptional(sourceSerialNumber, 200, "sourceSerialNumber");
        String newLogicalIdentifier = validOptional(sourceLogicalIdentifier, 300, "sourceLogicalIdentifier");
        this.sourceType = newSourceType;
        this.sourceDescription = newDescription;
        this.sourceManufacturer = newManufacturer;
        this.sourceModel = newModel;
        this.sourceSerialNumber = newSerialNumber;
        this.sourceLogicalIdentifier = newLogicalIdentifier;
        touch();
    }

    public void updateAcquisitionMetadata(
            AcquisitionMethod acquisitionMethod,
            String acquisitionLocation,
            String acquisitionToolName,
            String acquisitionToolVersion,
            String acquisitionNotes,
            Instant acquiredAt) {
        requireDescriptiveMetadataMutable();
        AcquisitionMethod newMethod = Objects.requireNonNull(acquisitionMethod, "acquisitionMethod must not be null");
        String newLocation = validOptional(acquisitionLocation, 300, "acquisitionLocation");
        String newToolName = validOptional(acquisitionToolName, 200, "acquisitionToolName");
        String newToolVersion = validOptional(acquisitionToolVersion, 100, "acquisitionToolVersion");
        String newNotes = validOptional(acquisitionNotes, 2000, "acquisitionNotes");
        Instant newAcquiredAt = validAcquiredAt(acquiredAt);
        this.acquisitionMethod = newMethod;
        this.acquisitionLocation = newLocation;
        this.acquisitionToolName = newToolName;
        this.acquisitionToolVersion = newToolVersion;
        this.acquisitionNotes = newNotes;
        this.acquiredAt = newAcquiredAt;
        touch();
    }

    public void transferTo(Operator newHolder) {
        requireNotReleased();
        currentHolder = Objects.requireNonNull(newHolder, "newHolder must not be null");
        touch();
    }

    public void seal() {
        if (status != EvidenceStatus.IN_CUSTODY) {
            throw new IllegalStateException("only evidence in custody can be sealed");
        }
        status = EvidenceStatus.SEALED;
        touch();
    }

    public void release() {
        requireNotReleased();
        status = EvidenceStatus.RELEASED;
        currentHolder = null;
        touch();
    }

    public UUID getId() {
        return id;
    }

    public CustodyCase getCustodyCase() {
        return custodyCase;
    }

    public String getReferenceTag() {
        return referenceTag;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public EvidenceStatus getStatus() {
        return status;
    }

    public Operator getCurrentHolder() {
        return currentHolder;
    }

    public Operator getUploadedBy() {
        return uploadedBy;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public String getSourceDescription() {
        return sourceDescription;
    }

    public String getSourceManufacturer() {
        return sourceManufacturer;
    }

    public String getSourceModel() {
        return sourceModel;
    }

    public String getSourceSerialNumber() {
        return sourceSerialNumber;
    }

    public String getSourceLogicalIdentifier() {
        return sourceLogicalIdentifier;
    }

    public AcquisitionMethod getAcquisitionMethod() {
        return acquisitionMethod;
    }

    public String getAcquisitionLocation() {
        return acquisitionLocation;
    }

    public String getAcquisitionToolName() {
        return acquisitionToolName;
    }

    public String getAcquisitionToolVersion() {
        return acquisitionToolVersion;
    }

    public String getAcquisitionNotes() {
        return acquisitionNotes;
    }

    public Instant getAcquiredAt() {
        return acquiredAt;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getFileExtension() {
        return fileExtension;
    }

    public String getMediaType() {
        return mediaType;
    }

    public long getFileSize() {
        return fileSize;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public String getContextualSha256() {
        return contextualSha256;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public long getCustodyEventCount() {
        return custodyEventCount;
    }

    public String getCustodyChainHeadHash() {
        return custodyChainHeadHash;
    }

    public void advanceCustodyChain(long sequenceNumber, String eventHash) {
        long expectedSequence = Math.addExact(custodyEventCount, 1L);
        if (sequenceNumber != expectedSequence) {
            throw new IllegalArgumentException("sequenceNumber must advance the custody chain by one");
        }
        if (custodyEventCount == 0 && !ZERO_CUSTODY_HASH.equals(custodyChainHeadHash)) {
            throw new IllegalStateException("an empty custody chain must have the zero hash head");
        }
        custodyChainHeadHash = validSha256(eventHash, "eventHash");
        custodyEventCount = sequenceNumber;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DigitalEvidence evidence)) {
            return false;
        }
        return id != null && id.equals(evidence.id);
    }

    @Override
    public int hashCode() {
        return DigitalEvidence.class.hashCode();
    }

    @Override
    public String toString() {
        return "DigitalEvidence{" + "id=" + id + ", referenceTag='" + referenceTag + '\'' + ", status=" + status + '}';
    }

    private static String validReferenceTag(String referenceTag) {
        String normalized = DigitalEvidenceNormalizer.normalizeReferenceTag(referenceTag);
        if (normalized != null && !normalized.matches(REFERENCE_TAG_PATTERN)) {
            throw new IllegalArgumentException(
                    "referenceTag must be 1 to 64 uppercase letters, digits, dots, underscores or hyphens");
        }
        return normalized;
    }

    private static String validTitle(String title) {
        String normalized = DigitalEvidenceNormalizer.normalizeRequired(title, "title");
        if (normalized.length() < 3 || normalized.length() > 200) {
            throw new IllegalArgumentException("title must be 3 to 200 characters");
        }
        return normalized;
    }

    private static String validOptional(String value, int maximumLength, String fieldName) {
        String normalized = DigitalEvidenceNormalizer.normalizeOptional(value);
        if (normalized != null && normalized.length() > maximumLength) {
            throw new IllegalArgumentException(fieldName + " must not exceed " + maximumLength + " characters");
        }
        return normalized;
    }

    private static String validOriginalFilename(String originalFilename) {
        String normalized = DigitalEvidenceNormalizer.normalizeRequired(originalFilename, "originalFilename");
        if (normalized.isEmpty() || normalized.length() > 255) {
            throw new IllegalArgumentException("originalFilename must be 1 to 255 characters");
        }
        if (normalized.equals(".")
                || normalized.equals("..")
                || normalized.indexOf('/') >= 0
                || normalized.indexOf('\\') >= 0
                || containsControlCharacter(normalized)) {
            throw new IllegalArgumentException("originalFilename must be a safe basename");
        }
        return normalized;
    }

    private static String deriveExtension(String originalFilename) {
        int separator = originalFilename.lastIndexOf('.');
        if (separator <= 0 || separator == originalFilename.length() - 1) {
            return null;
        }
        String extension = DigitalEvidenceNormalizer.normalizeExtension(originalFilename.substring(separator + 1));
        if (extension == null || extension.length() > 32) {
            return null;
        }
        return extension;
    }

    private static String validMediaType(String mediaType) {
        String normalized = DigitalEvidenceNormalizer.normalizeOptional(mediaType);
        if (normalized == null) {
            return DEFAULT_MEDIA_TYPE;
        }
        if (normalized.length() > 255 || containsControlCharacter(normalized)) {
            throw new IllegalArgumentException("mediaType must not exceed 255 safe characters");
        }
        return normalized;
    }

    private static long validFileSize(long fileSize) {
        if (fileSize <= 0) {
            throw new IllegalArgumentException("fileSize must be greater than zero");
        }
        return fileSize;
    }

    private static String validSha256(String value, String fieldName) {
        String normalized = DigitalEvidenceNormalizer.normalizeRequired(value, fieldName);
        if (!normalized.matches(SHA_256_PATTERN)) {
            throw new IllegalArgumentException(fieldName + " must be exactly 64 lowercase hexadecimal characters");
        }
        return normalized;
    }

    private static String validStorageKey(String storageKey) {
        String normalized = DigitalEvidenceNormalizer.normalizeRequired(storageKey, "storageKey");
        if (normalized.isEmpty() || normalized.length() > 500) {
            throw new IllegalArgumentException("storageKey must be 1 to 500 characters");
        }
        if (normalized.startsWith("/")
                || normalized.indexOf('\\') >= 0
                || normalized.indexOf(':') >= 0
                || containsControlCharacter(normalized)
                || Arrays.stream(normalized.split("/", -1))
                        .anyMatch(segment -> segment.isEmpty() || segment.equals(".") || segment.equals(".."))) {
            throw new IllegalArgumentException("storageKey must be a safe relative path");
        }
        return normalized;
    }

    private Instant validAcquiredAt(Instant acquiredAt) {
        if (acquiredAt == null) {
            return null;
        }
        Instant normalized = acquiredAt.truncatedTo(ChronoUnit.MICROS);
        if (normalized.isAfter(createdAt)) {
            throw new IllegalArgumentException("acquiredAt must not be after createdAt");
        }
        return normalized;
    }

    private static Instant validRegistrationTime(Instant registeredAt) {
        Instant value = Objects.requireNonNull(registeredAt, "registeredAt must not be null");
        if (!value.equals(value.truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalArgumentException("registeredAt must have microsecond precision");
        }
        return value;
    }

    private static boolean containsControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private void requireDescriptiveMetadataMutable() {
        if (status != EvidenceStatus.IN_CUSTODY) {
            throw new IllegalStateException("descriptive metadata can change only while evidence is in custody");
        }
    }

    private void requireNotReleased() {
        if (status == EvidenceStatus.RELEASED) {
            throw new IllegalStateException("released evidence is terminal");
        }
    }

    private void touch() {
        Instant now = nowAtMicrosecondPrecision();
        Instant current = updatedAt.truncatedTo(ChronoUnit.MICROS);
        updatedAt = now.isAfter(current) ? now : current.plus(1, ChronoUnit.MICROS);
    }

    private static Instant nowAtMicrosecondPrecision() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
