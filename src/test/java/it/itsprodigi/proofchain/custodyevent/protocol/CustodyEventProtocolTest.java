package it.itsprodigi.proofchain.custodyevent.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.itsprodigi.proofchain.custodyevent.domain.EventType;
import it.itsprodigi.proofchain.evidence.domain.AcquisitionMethod;
import it.itsprodigi.proofchain.evidence.domain.EvidenceStatus;
import it.itsprodigi.proofchain.evidence.domain.SourceType;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustodyEventProtocolTest {

    private static final UUID CASE_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID EVIDENCE_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID OPERATOR_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID PREVIOUS_HOLDER_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID NEW_HOLDER_ID = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID UPLOADED_BY_ID = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final String A_HASH = "a".repeat(64);
    private static final String B_HASH = "b".repeat(64);
    private static final String C_HASH = "c".repeat(64);
    private static final String D_HASH = "d".repeat(64);
    private static final String REGISTERED_HASH = "4d29074e712e6c3eb0c0f3e3b148191a6b9431ca1798a9a20053006b6296e329";
    private static final String TRANSFERRED_HASH = "33ed9df27953f702c59a5c9f40d187c3c05708c09b9a0b8c2690add112fed0ee";
    private static final String METADATA_HASH = "47d7b06e486d7f2815be39ca8dba600785dd8d425382a4f70c8894dd23bd0693";
    private static final String INTEGRITY_HASH = "23f33ef80ca7fc4141f14eee2f5acbde0c31409dbd9e669d93e3c941fbfc6455";
    private static final String SEALED_HASH = "afeb2b1deef4cffb6d2435554b2ab66aa90139ae0b358777c1229469916df7b0";
    private static final String RELEASED_HASH = "cdfa7a903b7ee05780a3bd645e57d8e81e6b4c43df25b2820f8ad7949c112458";

    private static final String REGISTERED_JSON = "{"
            + "\"acquiredAt\":null,"
            + "\"acquisitionLocation\":null,"
            + "\"acquisitionMethod\":\"PHYSICAL\","
            + "\"acquisitionNotes\":null,"
            + "\"acquisitionToolName\":\"Imager\","
            + "\"acquisitionToolVersion\":null,"
            + "\"backfilled\":false,"
            + "\"contentSha256\":\""
            + A_HASH
            + "\","
            + "\"contextualSha256\":\""
            + B_HASH
            + "\","
            + "\"description\":null,"
            + "\"fileExtension\":\"e01\","
            + "\"fileSize\":4096,"
            + "\"initialHolderId\":\""
            + PREVIOUS_HOLDER_ID
            + "\","
            + "\"mediaType\":\"application/octet-stream\","
            + "\"originalFilename\":\"image.E01\","
            + "\"referenceTag\":\"EV-01\","
            + "\"sourceDescription\":\"Workstation\\nlab\","
            + "\"sourceLogicalIdentifier\":null,"
            + "\"sourceManufacturer\":null,"
            + "\"sourceModel\":null,"
            + "\"sourceSerialNumber\":null,"
            + "\"sourceType\":\"DEVICE\","
            + "\"status\":\"IN_CUSTODY\","
            + "\"title\":\"Disk \\\"A\\\" \\\\ 🔐\","
            + "\"uploadedById\":\""
            + UPLOADED_BY_ID
            + "\"}";

    private static final String TRANSFERRED_JSON = "{"
            + "\"newHolderId\":\""
            + NEW_HOLDER_ID
            + "\","
            + "\"previousHolderId\":\""
            + PREVIOUS_HOLDER_ID
            + "\","
            + "\"reason\":\"Move \\\"A\\\"\\\\B\\nnow\"}";

    private static final String BEFORE_METADATA_JSON = "{"
            + "\"acquiredAt\":null,"
            + "\"acquisitionLocation\":null,"
            + "\"acquisitionMethod\":\"LOGICAL\","
            + "\"acquisitionNotes\":null,"
            + "\"acquisitionToolName\":null,"
            + "\"acquisitionToolVersion\":null,"
            + "\"description\":null,"
            + "\"sourceDescription\":null,"
            + "\"sourceLogicalIdentifier\":null,"
            + "\"sourceManufacturer\":null,"
            + "\"sourceModel\":null,"
            + "\"sourceSerialNumber\":null,"
            + "\"sourceType\":\"FILESYSTEM\","
            + "\"title\":\"Before\"}";

    private static final String AFTER_METADATA_JSON = "{"
            + "\"acquiredAt\":\"2026-07-29T23:59:59.000000Z\","
            + "\"acquisitionLocation\":\"Lab\","
            + "\"acquisitionMethod\":\"LOGICAL\","
            + "\"acquisitionNotes\":\"line\\tbreak\","
            + "\"acquisitionToolName\":null,"
            + "\"acquisitionToolVersion\":\"2.0\","
            + "\"description\":\"Après 🔐\","
            + "\"sourceDescription\":null,"
            + "\"sourceLogicalIdentifier\":\"/dev/sda\","
            + "\"sourceManufacturer\":null,"
            + "\"sourceModel\":null,"
            + "\"sourceSerialNumber\":null,"
            + "\"sourceType\":\"FILESYSTEM\","
            + "\"title\":\"After\"}";

    private static final String METADATA_JSON = "{\"after\":"
            + AFTER_METADATA_JSON
            + ",\"before\":"
            + BEFORE_METADATA_JSON
            + ",\"reason\":\"Corrected metadata\"}";

    private static final String INTEGRITY_JSON = "{"
            + "\"actualContentSha256\":\""
            + C_HASH
            + "\","
            + "\"algorithm\":\"SHA-256\","
            + "\"expectedContentSha256\":\""
            + C_HASH
            + "\","
            + "\"fileSize\":4096,"
            + "\"valid\":true}";

    private static final String SEALED_JSON = "{"
            + "\"holderId\":\""
            + PREVIOUS_HOLDER_ID
            + "\","
            + "\"newStatus\":\"SEALED\","
            + "\"previousStatus\":\"IN_CUSTODY\","
            + "\"reason\":\"Seal approved\"}";

    private static final String RELEASED_JSON = "{"
            + "\"newHolderId\":null,"
            + "\"newStatus\":\"RELEASED\","
            + "\"previousHolderId\":\""
            + PREVIOUS_HOLDER_ID
            + "\","
            + "\"previousStatus\":\"SEALED\","
            + "\"reason\":\"Court release\"}";

    @Test
    void canonicalizesAndHashesTheSixCommittedProtocolVectors() {
        List<Vector> vectors = List.of(
                vector(
                        1,
                        EventType.EVIDENCE_REGISTERED,
                        "2026-07-30T00:00:00.000000Z",
                        registeredPayload(),
                        CustodyEventHashing.ZERO_HASH,
                        REGISTERED_JSON,
                        REGISTERED_HASH),
                vector(
                        2,
                        EventType.CUSTODY_TRANSFERRED,
                        "2026-07-30T00:00:00.000001Z",
                        new CustodyTransferredPayload(PREVIOUS_HOLDER_ID, NEW_HOLDER_ID, "  Move \"A\"\\B\nnow  "),
                        REGISTERED_HASH,
                        TRANSFERRED_JSON,
                        TRANSFERRED_HASH),
                vector(
                        3,
                        EventType.METADATA_UPDATED,
                        "2026-07-30T00:00:00.123456Z",
                        new MetadataUpdatedPayload(beforeMetadata(), afterMetadata(), "Corrected metadata"),
                        TRANSFERRED_HASH,
                        METADATA_JSON,
                        METADATA_HASH),
                vector(
                        4,
                        EventType.INTEGRITY_VERIFIED,
                        "2026-07-30T00:00:00.654321Z",
                        new IntegrityVerifiedPayload("SHA-256", C_HASH, C_HASH, true, 4096),
                        METADATA_HASH,
                        INTEGRITY_JSON,
                        INTEGRITY_HASH),
                vector(
                        5,
                        EventType.EVIDENCE_SEALED,
                        "2026-07-30T00:00:01.000000Z",
                        new EvidenceSealedPayload(
                                EvidenceStatus.IN_CUSTODY, EvidenceStatus.SEALED, PREVIOUS_HOLDER_ID, "Seal approved"),
                        INTEGRITY_HASH,
                        SEALED_JSON,
                        SEALED_HASH),
                vector(
                        6,
                        EventType.EVIDENCE_RELEASED,
                        "2026-07-30T00:00:02.000000Z",
                        new EvidenceReleasedPayload(
                                EvidenceStatus.SEALED,
                                EvidenceStatus.RELEASED,
                                PREVIOUS_HOLDER_ID,
                                null,
                                "Court release"),
                        SEALED_HASH,
                        RELEASED_JSON,
                        RELEASED_HASH));

        assertThat(vectors).allSatisfy(vector -> {
            String canonical = CustodyEventCanonicalizer.canonicalize(vector.event());
            assertThat(CustodyEventCanonicalizer.canonicalizePayload(
                            vector.event().payload()))
                    .isEqualTo(vector.payloadJson());
            assertThat(canonical).isEqualTo(vector.canonicalJson());
            assertThat(CustodyEventCanonicalizer.canonicalBytes(vector.event()))
                    .isEqualTo(vector.canonicalJson().getBytes(StandardCharsets.UTF_8));
            assertThat(canonical.charAt(0)).isEqualTo('{');
            assertThat(CustodyEventHashing.eventHash(vector.event())).isEqualTo(vector.eventHash());
        });
        assertThat(vectors.getFirst().event().previousHash()).isEqualTo(CustodyEventHashing.ZERO_HASH);
        assertThat(vectors.subList(1, vectors.size()))
                .allSatisfy(vector ->
                        assertThat(vector.event().previousHash()).isNotEqualTo(CustodyEventHashing.ZERO_HASH));
    }

    @Test
    void canonicalFormAndHashIgnoreDefaultLocaleAndTimezoneAndReactToEveryFieldChange() {
        Vector vector = vector(
                2,
                EventType.CUSTODY_TRANSFERRED,
                "2026-07-30T00:00:00.000001Z",
                new CustodyTransferredPayload(PREVIOUS_HOLDER_ID, NEW_HOLDER_ID, "Move"),
                REGISTERED_HASH,
                "{\"newHolderId\":\""
                        + NEW_HOLDER_ID
                        + "\",\"previousHolderId\":\""
                        + PREVIOUS_HOLDER_ID
                        + "\",\"reason\":\"Move\"}",
                "1ee0a36efadc42c548cb2958570248e00ac96fa01c9342a10dc31c1d21c04d5b");
        Locale originalLocale = Locale.getDefault();
        TimeZone originalTimeZone = TimeZone.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
            assertThat(CustodyEventCanonicalizer.canonicalize(vector.event())).isEqualTo(vector.canonicalJson());
            assertThat(CustodyEventHashing.eventHash(vector.event())).isEqualTo(vector.eventHash());
        } finally {
            Locale.setDefault(originalLocale);
            TimeZone.setDefault(originalTimeZone);
        }

        CanonicalCustodyEvent changed = event(
                2,
                EventType.CUSTODY_TRANSFERRED,
                "2026-07-30T00:00:00.000001Z",
                new CustodyTransferredPayload(PREVIOUS_HOLDER_ID, NEW_HOLDER_ID, "Changed"),
                REGISTERED_HASH);
        assertThat(CustodyEventHashing.eventHash(changed)).isNotEqualTo(vector.eventHash());
    }

    @Test
    void closesPayloadTypesAndRejectsInvalidEventSpecificAndEnvelopeValues() {
        assertThat(CustodyEventPayload.class.getPermittedSubclasses())
                .containsExactlyInAnyOrder(
                        EvidenceRegisteredPayload.class,
                        CustodyTransferredPayload.class,
                        MetadataUpdatedPayload.class,
                        IntegrityVerifiedPayload.class,
                        EvidenceSealedPayload.class,
                        EvidenceReleasedPayload.class);
        assertThat(EventType.values())
                .extracting(eventType -> eventType)
                .containsExactlyInAnyOrder(
                        registeredPayload().eventType(),
                        new CustodyTransferredPayload(PREVIOUS_HOLDER_ID, NEW_HOLDER_ID, "Transfer").eventType(),
                        new MetadataUpdatedPayload(beforeMetadata(), afterMetadata(), "Update").eventType(),
                        new IntegrityVerifiedPayload("SHA-256", C_HASH, C_HASH, true, 1).eventType(),
                        new EvidenceSealedPayload(
                                        EvidenceStatus.IN_CUSTODY, EvidenceStatus.SEALED, PREVIOUS_HOLDER_ID, "Seal")
                                .eventType(),
                        new EvidenceReleasedPayload(
                                        EvidenceStatus.IN_CUSTODY,
                                        EvidenceStatus.RELEASED,
                                        PREVIOUS_HOLDER_ID,
                                        null,
                                        "Release")
                                .eventType());

        assertThat(new CustodyTransferredPayload(PREVIOUS_HOLDER_ID, NEW_HOLDER_ID, "  valid  ").reason())
                .isEqualTo("valid");
        assertThat(new CustodyTransferredPayload(PREVIOUS_HOLDER_ID, NEW_HOLDER_ID, "x".repeat(1000)).reason())
                .hasSize(1000);
        assertThatThrownBy(() -> new CustodyTransferredPayload(PREVIOUS_HOLDER_ID, NEW_HOLDER_ID, "x".repeat(1001)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CustodyTransferredPayload(PREVIOUS_HOLDER_ID, NEW_HOLDER_ID, "   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CustodyTransferredPayload(PREVIOUS_HOLDER_ID, PREVIOUS_HOLDER_ID, "Move"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new IntegrityVerifiedPayload("SHA256", C_HASH, C_HASH, true, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntegrityVerifiedPayload("SHA-256", C_HASH, D_HASH, true, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntegrityVerifiedPayload("SHA-256", C_HASH, D_HASH, false, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new IntegrityVerifiedPayload("SHA-256", C_HASH, D_HASH, false, 1).valid())
                .isFalse();

        assertThatThrownBy(() -> new EvidenceSealedPayload(
                        EvidenceStatus.SEALED, EvidenceStatus.SEALED, PREVIOUS_HOLDER_ID, "Seal"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceReleasedPayload(
                        EvidenceStatus.RELEASED, EvidenceStatus.RELEASED, PREVIOUS_HOLDER_ID, null, "Release"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceReleasedPayload(
                        EvidenceStatus.SEALED, EvidenceStatus.RELEASED, PREVIOUS_HOLDER_ID, NEW_HOLDER_ID, "Release"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new EvidenceMetadataSnapshot(
                        "Title",
                        null,
                        SourceType.DEVICE,
                        null,
                        null,
                        null,
                        null,
                        null,
                        AcquisitionMethod.PHYSICAL,
                        Instant.parse("2026-07-30T00:00:00.123456789Z"),
                        null,
                        null,
                        null,
                        null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new CanonicalCustodyEvent(
                        eventId(1),
                        CASE_ID,
                        EVIDENCE_ID,
                        OPERATOR_ID,
                        OperatorRole.EVIDENCE_OFFICER,
                        1,
                        EventType.CUSTODY_TRANSFERRED,
                        Instant.parse("2026-07-30T00:00:00Z"),
                        1,
                        registeredPayload(),
                        CustodyEventHashing.ZERO_HASH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("payload must match eventType");
        assertThatThrownBy(() -> new CanonicalCustodyEvent(
                        eventId(1),
                        CASE_ID,
                        EVIDENCE_ID,
                        OPERATOR_ID,
                        OperatorRole.EVIDENCE_OFFICER,
                        1,
                        EventType.EVIDENCE_REGISTERED,
                        Instant.parse("2026-07-30T00:00:00Z"),
                        2,
                        registeredPayload(),
                        CustodyEventHashing.ZERO_HASH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("payloadVersion must be 1");
        assertThatThrownBy(() -> new CanonicalCustodyEvent(
                        UUID.fromString("10000000-0000-3000-8000-000000000001"),
                        CASE_ID,
                        EVIDENCE_ID,
                        OPERATOR_ID,
                        OperatorRole.EVIDENCE_OFFICER,
                        1,
                        EventType.EVIDENCE_REGISTERED,
                        Instant.parse("2026-07-30T00:00:00Z"),
                        1,
                        registeredPayload(),
                        CustodyEventHashing.ZERO_HASH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustodyEventHashing.eventHash(
                        event(
                                1,
                                EventType.EVIDENCE_REGISTERED,
                                "2026-07-30T00:00:00.000000Z",
                                registeredPayload(),
                                CustodyEventHashing.ZERO_HASH),
                        2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("hashVersion must be 1");
    }

    private static EvidenceRegisteredPayload registeredPayload() {
        return new EvidenceRegisteredPayload(
                false,
                " ev-01 ",
                " Disk \"A\" \\ 🔐 ",
                null,
                EvidenceStatus.IN_CUSTODY,
                SourceType.DEVICE,
                "Workstation\nlab",
                null,
                null,
                null,
                null,
                AcquisitionMethod.PHYSICAL,
                null,
                null,
                "Imager",
                null,
                null,
                "image.E01",
                " E01 ",
                "application/octet-stream",
                4096,
                A_HASH,
                B_HASH,
                UPLOADED_BY_ID,
                PREVIOUS_HOLDER_ID);
    }

    private static EvidenceMetadataSnapshot beforeMetadata() {
        return new EvidenceMetadataSnapshot(
                "Before",
                null,
                SourceType.FILESYSTEM,
                null,
                null,
                null,
                null,
                null,
                AcquisitionMethod.LOGICAL,
                null,
                null,
                null,
                null,
                null);
    }

    private static EvidenceMetadataSnapshot afterMetadata() {
        return new EvidenceMetadataSnapshot(
                "After",
                "Après 🔐",
                SourceType.FILESYSTEM,
                null,
                null,
                null,
                null,
                "/dev/sda",
                AcquisitionMethod.LOGICAL,
                Instant.parse("2026-07-29T23:59:59Z"),
                "Lab",
                null,
                "2.0",
                "line\tbreak");
    }

    private static Vector vector(
            int sequenceNumber,
            EventType eventType,
            String occurredAt,
            CustodyEventPayload payload,
            String previousHash,
            String payloadJson,
            String eventHash) {
        CanonicalCustodyEvent event = event(sequenceNumber, eventType, occurredAt, payload, previousHash);
        return new Vector(
                event,
                payloadJson,
                fixedCanonicalJson(
                        eventId(sequenceNumber), eventType, occurredAt, payloadJson, previousHash, sequenceNumber),
                eventHash);
    }

    private static CanonicalCustodyEvent event(
            int sequenceNumber,
            EventType eventType,
            String occurredAt,
            CustodyEventPayload payload,
            String previousHash) {
        return new CanonicalCustodyEvent(
                eventId(sequenceNumber),
                CASE_ID,
                EVIDENCE_ID,
                OPERATOR_ID,
                OperatorRole.EVIDENCE_OFFICER,
                sequenceNumber,
                eventType,
                Instant.parse(occurredAt),
                CanonicalCustodyEvent.PAYLOAD_VERSION,
                payload,
                previousHash);
    }

    private static String fixedCanonicalJson(
            UUID eventId,
            EventType eventType,
            String occurredAt,
            String payloadJson,
            String previousHash,
            long sequenceNumber) {
        return "{\"actorRole\":\"EVIDENCE_OFFICER\","
                + "\"caseId\":\""
                + CASE_ID
                + "\","
                + "\"eventId\":\""
                + eventId
                + "\","
                + "\"eventType\":\""
                + eventType
                + "\","
                + "\"evidenceId\":\""
                + EVIDENCE_ID
                + "\","
                + "\"occurredAt\":\""
                + occurredAt
                + "\","
                + "\"operatorId\":\""
                + OPERATOR_ID
                + "\","
                + "\"payload\":"
                + payloadJson
                + ",\"payloadVersion\":1,"
                + "\"previousHash\":\""
                + previousHash
                + "\","
                + "\"sequenceNumber\":"
                + sequenceNumber
                + '}';
    }

    private static UUID eventId(int sequenceNumber) {
        return UUID.fromString("10000000-0000-4000-8000-00000000000" + sequenceNumber);
    }

    private record Vector(CanonicalCustodyEvent event, String payloadJson, String canonicalJson, String eventHash) {}
}
