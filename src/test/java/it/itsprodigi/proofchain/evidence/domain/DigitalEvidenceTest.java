package it.itsprodigi.proofchain.evidence.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.itsprodigi.proofchain.custodycase.domain.CasePriority;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class DigitalEvidenceTest {

    private static final String BCRYPT_HASH = "$2a$10$01234567890123456789012345678901234567890123456789012";
    private static final String CONTENT_SHA_256 = "a".repeat(64);
    private static final String CONTEXTUAL_SHA_256 = "b".repeat(64);

    @Test
    void createsEvidenceWithCanonicalMetadataAndImmutableTechnicalContent() {
        EvidenceFixture fixture = new EvidenceFixture();

        DigitalEvidence evidence = fixture.create();

        assertThat(evidence.getId().version()).isEqualTo(4);
        assertThat(evidence.getCustodyCase()).isSameAs(fixture.custodyCase);
        assertThat(evidence.getCurrentHolder()).isSameAs(fixture.currentHolder);
        assertThat(evidence.getUploadedBy()).isSameAs(fixture.uploadedBy);
        assertThat(evidence.getReferenceTag()).isEqualTo("EV-001");
        assertThat(evidence.getTitle()).isEqualTo("Disk image");
        assertThat(evidence.getDescription()).isEqualTo("Forensic image");
        assertThat(evidence.getStatus()).isEqualTo(EvidenceStatus.IN_CUSTODY);
        assertThat(evidence.getSourceType()).isEqualTo(SourceType.DEVICE);
        assertThat(evidence.getSourceDescription()).isEqualTo("MacBook Pro Édition");
        assertThat(evidence.getSourceManufacturer()).isEqualTo("Acme");
        assertThat(evidence.getSourceModel()).isEqualTo("Model X");
        assertThat(evidence.getSourceSerialNumber()).isEqualTo("SN-001");
        assertThat(evidence.getSourceLogicalIdentifier()).isEqualTo("disk0");
        assertThat(evidence.getAcquisitionMethod()).isEqualTo(AcquisitionMethod.LOGICAL);
        assertThat(evidence.getAcquisitionLocation()).isEqualTo("Lab A");
        assertThat(evidence.getAcquisitionToolName()).isEqualTo("Tool Pro");
        assertThat(evidence.getAcquisitionToolVersion()).isEqualTo("1.0");
        assertThat(evidence.getAcquisitionNotes()).isEqualTo("Read-only acquisition");
        assertThat(evidence.getAcquiredAt()).isEqualTo(Instant.EPOCH);
        assertThat(evidence.getOriginalFilename()).isEqualTo("Capture.TAR.GZ");
        assertThat(evidence.getFileExtension()).isEqualTo("gz");
        assertThat(evidence.getMediaType()).isEqualTo("application/octet-stream");
        assertThat(evidence.getFileSize()).isEqualTo(4096L);
        assertThat(evidence.getContentSha256()).isEqualTo(CONTENT_SHA_256);
        assertThat(evidence.getContextualSha256()).isEqualTo(CONTEXTUAL_SHA_256);
        assertThat(evidence.getStorageKey()).isEqualTo(fixture.storageKey);
        assertThat(evidence.getCreatedAt()).isEqualTo(evidence.getCreatedAt().truncatedTo(ChronoUnit.MICROS));
        assertThat(evidence.getUpdatedAt()).isEqualTo(evidence.getCreatedAt());
        assertThat(evidence.getVersion()).isZero();
    }

    @Test
    void preservesUnicodeAndCaseExceptForReferenceTagAndDerivedExtension() {
        EvidenceFixture invalidFixture = new EvidenceFixture();
        invalidFixture.referenceTag = "  tag_à  ";
        assertThatThrownBy(invalidFixture::create).isInstanceOf(IllegalArgumentException.class);

        EvidenceFixture fixture = new EvidenceFixture();
        fixture.referenceTag = "  tag_01  ";
        fixture.title = "  Déjà Vu  ";
        fixture.description = "   ";
        fixture.sourceDescription = "  Dispositivo Élite  ";
        fixture.sourceManufacturer = " ";
        fixture.sourceModel = " ";
        fixture.sourceSerialNumber = " ";
        fixture.sourceLogicalIdentifier = " ";
        fixture.acquisitionLocation = " ";
        fixture.acquisitionToolName = " ";
        fixture.acquisitionToolVersion = " ";
        fixture.acquisitionNotes = " ";
        fixture.originalFilename = "  Café.ÄBC  ";
        fixture.mediaType = "  Application/X-Custom  ";

        DigitalEvidence evidence = fixture.create();

        assertThat(evidence.getReferenceTag()).isEqualTo("TAG_01");
        assertThat(evidence.getTitle()).isEqualTo("Déjà Vu");
        assertThat(evidence.getDescription()).isNull();
        assertThat(evidence.getSourceDescription()).isEqualTo("Dispositivo Élite");
        assertThat(evidence.getSourceManufacturer()).isNull();
        assertThat(evidence.getSourceModel()).isNull();
        assertThat(evidence.getSourceSerialNumber()).isNull();
        assertThat(evidence.getSourceLogicalIdentifier()).isNull();
        assertThat(evidence.getAcquisitionLocation()).isNull();
        assertThat(evidence.getAcquisitionToolName()).isNull();
        assertThat(evidence.getAcquisitionToolVersion()).isNull();
        assertThat(evidence.getAcquisitionNotes()).isNull();
        assertThat(evidence.getOriginalFilename()).isEqualTo("Café.ÄBC");
        assertThat(evidence.getFileExtension()).isEqualTo("äbc");
        assertThat(evidence.getMediaType()).isEqualTo("Application/X-Custom");
    }

    @Test
    void exposesOnlyTheFrozenEnums() {
        assertThat(EvidenceStatus.values())
                .containsExactly(EvidenceStatus.IN_CUSTODY, EvidenceStatus.SEALED, EvidenceStatus.RELEASED);
        assertThat(SourceType.values())
                .containsExactly(
                        SourceType.DEVICE,
                        SourceType.FILESYSTEM,
                        SourceType.REMOVABLE_MEDIA,
                        SourceType.CLOUD_SERVICE,
                        SourceType.NETWORK_CAPTURE,
                        SourceType.EMAIL,
                        SourceType.DATABASE,
                        SourceType.OTHER,
                        SourceType.UNKNOWN);
        assertThat(AcquisitionMethod.values())
                .containsExactly(
                        AcquisitionMethod.PHYSICAL,
                        AcquisitionMethod.LOGICAL,
                        AcquisitionMethod.EXPORT,
                        AcquisitionMethod.CAPTURE,
                        AcquisitionMethod.MANUAL_UPLOAD,
                        AcquisitionMethod.OTHER,
                        AcquisitionMethod.UNKNOWN);
    }

    @Test
    void rejectsInvalidRequiredAndBoundedDescriptiveMetadata() {
        assertInvalid(fixture -> fixture.referenceTag = "-INVALID");
        assertInvalid(fixture -> fixture.referenceTag = "A".repeat(65));
        assertInvalid(fixture -> fixture.title = " x ");
        assertInvalid(fixture -> fixture.title = "x".repeat(201));
        assertInvalid(fixture -> fixture.description = "x".repeat(2001));
        assertInvalid(fixture -> fixture.sourceDescription = "x".repeat(501));
        assertInvalid(fixture -> fixture.sourceManufacturer = "x".repeat(101));
        assertInvalid(fixture -> fixture.sourceModel = "x".repeat(101));
        assertInvalid(fixture -> fixture.sourceSerialNumber = "x".repeat(201));
        assertInvalid(fixture -> fixture.sourceLogicalIdentifier = "x".repeat(301));
        assertInvalid(fixture -> fixture.acquisitionLocation = "x".repeat(301));
        assertInvalid(fixture -> fixture.acquisitionToolName = "x".repeat(201));
        assertInvalid(fixture -> fixture.acquisitionToolVersion = "x".repeat(101));
        assertInvalid(fixture -> fixture.acquisitionNotes = "x".repeat(2001));
        assertInvalid(fixture -> fixture.sourceType = null);
        assertInvalid(fixture -> fixture.acquisitionMethod = null);
        assertInvalid(fixture -> fixture.custodyCase = null);
        assertInvalid(fixture -> fixture.currentHolder = null);
        assertInvalid(fixture -> fixture.uploadedBy = null);
    }

    @Test
    void rejectsUnsafeOrInvalidTechnicalContentMetadata() {
        for (String filename : new String[] {"", ".", "..", "folder/file.bin", "folder\\file.bin", "bad\n.bin"}) {
            assertInvalid(fixture -> fixture.originalFilename = filename);
        }
        assertInvalid(fixture -> fixture.originalFilename = "x".repeat(256));
        assertInvalid(fixture -> fixture.originalFilename = "file." + "x".repeat(33));
        assertInvalid(fixture -> fixture.mediaType = "x".repeat(256));
        assertInvalid(fixture -> fixture.mediaType = "application/\njson");
        assertInvalid(fixture -> fixture.fileSize = 0);
        assertInvalid(fixture -> fixture.fileSize = -1);
        assertInvalid(fixture -> fixture.contentSha256 = "A".repeat(64));
        assertInvalid(fixture -> fixture.contentSha256 = "g".repeat(64));
        assertInvalid(fixture -> fixture.contentSha256 = "a".repeat(63));
        assertInvalid(fixture -> fixture.contextualSha256 = "B".repeat(64));
        for (String storageKey : new String[] {
            "/absolute/content.bin",
            "../content.bin",
            "cases/../content.bin",
            "cases//content.bin",
            "cases\\content.bin",
            "C:/content.bin",
            "cases/content\n.bin"
        }) {
            assertInvalid(fixture -> fixture.storageKey = storageKey);
        }
        assertInvalid(fixture -> fixture.storageKey = "x".repeat(501));
    }

    @Test
    void acquiredAtIsOptionalMicrosecondAlignedAndCannotFollowCreation() {
        EvidenceFixture fixture = new EvidenceFixture();
        fixture.acquiredAt = Instant.parse("2026-01-01T12:34:56.123456789Z");

        DigitalEvidence evidence = fixture.create();

        assertThat(evidence.getAcquiredAt()).isEqualTo(Instant.parse("2026-01-01T12:34:56.123456Z"));

        fixture = new EvidenceFixture();
        fixture.acquiredAt = null;
        assertThat(fixture.create().getAcquiredAt()).isNull();

        assertInvalid(future -> future.acquiredAt = Instant.now().plusSeconds(60));
    }

    @Test
    void updatesDescriptiveMetadataOnlyWhileInCustodyAndTransfersWhileSealed() {
        EvidenceFixture fixture = new EvidenceFixture();
        DigitalEvidence evidence = fixture.create();
        Operator newHolder = operator("new-holder");
        Instant initialUpdatedAt = evidence.getUpdatedAt();

        evidence.updateMetadata(" Updated title ", " Updated description ");
        assertThat(evidence.getReferenceTag()).isEqualTo("EV-001");
        evidence.updateSourceMetadata(
                SourceType.CLOUD_SERVICE, " Cloud source ", " Vendor ", " Model ", " Serial ", " tenant/id ");
        assertThat(evidence.getReferenceTag()).isEqualTo("EV-001");
        evidence.updateAcquisitionMetadata(
                AcquisitionMethod.EXPORT,
                " Remote ",
                " Export Tool ",
                " 2.0 ",
                " Export notes ",
                Instant.EPOCH.plusSeconds(1));
        assertThat(evidence.getReferenceTag()).isEqualTo("EV-001");

        assertThat(evidence.getTitle()).isEqualTo("Updated title");
        assertThat(evidence.getDescription()).isEqualTo("Updated description");
        assertThat(evidence.getSourceType()).isEqualTo(SourceType.CLOUD_SERVICE);
        assertThat(evidence.getSourceLogicalIdentifier()).isEqualTo("tenant/id");
        assertThat(evidence.getAcquisitionMethod()).isEqualTo(AcquisitionMethod.EXPORT);
        assertThat(evidence.getAcquisitionNotes()).isEqualTo("Export notes");
        assertThat(evidence.getUpdatedAt()).isAfter(initialUpdatedAt);

        evidence.seal();
        evidence.transferTo(newHolder);

        assertThat(evidence.getStatus()).isEqualTo(EvidenceStatus.SEALED);
        assertThat(evidence.getCurrentHolder()).isSameAs(newHolder);
        assertThatThrownBy(() -> evidence.updateMetadata("Another title", null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> evidence.updateSourceMetadata(SourceType.OTHER, null, null, null, null, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () -> evidence.updateAcquisitionMetadata(AcquisitionMethod.OTHER, null, null, null, null, null))
                .isInstanceOf(IllegalStateException.class);

        assertImmutableConstructionFields(evidence, fixture);
    }

    @Test
    void enforcesTheLifecycleGraphAndClearsHolderOnRelease() {
        DigitalEvidence directRelease = new EvidenceFixture().create();
        directRelease.release();
        assertThat(directRelease.getStatus()).isEqualTo(EvidenceStatus.RELEASED);
        assertThat(directRelease.getCurrentHolder()).isNull();
        assertThatThrownBy(directRelease::seal).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(directRelease::release).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> directRelease.transferTo(operator("late-holder")))
                .isInstanceOf(IllegalStateException.class);

        DigitalEvidence sealedRelease = new EvidenceFixture().create();
        sealedRelease.seal();
        assertThatThrownBy(sealedRelease::seal).isInstanceOf(IllegalStateException.class);
        sealedRelease.release();
        assertThat(sealedRelease.getStatus()).isEqualTo(EvidenceStatus.RELEASED);
        assertThat(sealedRelease.getCurrentHolder()).isNull();
    }

    @Test
    void derivesNoExtensionForBasenamesWithoutAUsableSuffixAndKeepsStringOutputSafe() {
        EvidenceFixture fixture = new EvidenceFixture();
        fixture.originalFilename = "README";
        DigitalEvidence evidence = fixture.create();

        assertThat(evidence.getFileExtension()).isNull();
        assertThat(evidence.toString())
                .contains(evidence.getId().toString(), "EV-001", "IN_CUSTODY")
                .doesNotContain(CONTENT_SHA_256, CONTEXTUAL_SHA_256, fixture.storageKey);
    }

    private static void assertInvalid(Consumer<EvidenceFixture> mutation) {
        EvidenceFixture fixture = new EvidenceFixture();
        mutation.accept(fixture);
        assertThatThrownBy(fixture::create).isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
    }

    private static void assertImmutableConstructionFields(DigitalEvidence evidence, EvidenceFixture fixture) {
        assertThat(evidence.getCustodyCase()).isSameAs(fixture.custodyCase);
        assertThat(evidence.getUploadedBy()).isSameAs(fixture.uploadedBy);
        assertThat(evidence.getOriginalFilename()).isEqualTo("Capture.TAR.GZ");
        assertThat(evidence.getFileExtension()).isEqualTo("gz");
        assertThat(evidence.getMediaType()).isEqualTo("application/octet-stream");
        assertThat(evidence.getFileSize()).isEqualTo(4096L);
        assertThat(evidence.getContentSha256()).isEqualTo(CONTENT_SHA_256);
        assertThat(evidence.getContextualSha256()).isEqualTo(CONTEXTUAL_SHA_256);
        assertThat(evidence.getStorageKey()).isEqualTo(fixture.storageKey);
        assertThat(evidence.getCreatedAt()).isEqualTo(evidence.getCreatedAt().truncatedTo(ChronoUnit.MICROS));
    }

    private static Operator operator(String username) {
        return Operator.create(
                username,
                username + "@example.com",
                BCRYPT_HASH,
                "Evidence",
                "Operator",
                OperatorRole.EVIDENCE_OFFICER);
    }

    private static CustodyCase custodyCase(Operator creator) {
        return CustodyCase.create("Evidence case", null, null, null, null, CasePriority.HIGH, creator);
    }

    private static final class EvidenceFixture {

        private Operator uploadedBy = operator("uploader");
        private Operator currentHolder = operator("holder");
        private CustodyCase custodyCase = custodyCase(uploadedBy);
        private String referenceTag = "  ev-001  ";
        private String title = "  Disk image  ";
        private String description = "  Forensic image  ";
        private SourceType sourceType = SourceType.DEVICE;
        private String sourceDescription = "  MacBook Pro Édition  ";
        private String sourceManufacturer = "  Acme  ";
        private String sourceModel = "  Model X  ";
        private String sourceSerialNumber = "  SN-001  ";
        private String sourceLogicalIdentifier = "  disk0  ";
        private AcquisitionMethod acquisitionMethod = AcquisitionMethod.LOGICAL;
        private String acquisitionLocation = "  Lab A  ";
        private String acquisitionToolName = "  Tool Pro  ";
        private String acquisitionToolVersion = "  1.0  ";
        private String acquisitionNotes = "  Read-only acquisition  ";
        private Instant acquiredAt = Instant.EPOCH;
        private String originalFilename = "  Capture.TAR.GZ  ";
        private String mediaType;
        private long fileSize = 4096L;
        private String contentSha256 = CONTENT_SHA_256;
        private String contextualSha256 = CONTEXTUAL_SHA_256;
        private String storageKey = "cases/case-id/evidences/evidence-id/content.bin";

        private DigitalEvidence create() {
            return DigitalEvidence.create(
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
                    storageKey);
        }
    }
}
