package it.itsprodigi.proofchain.custodyevent.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import it.itsprodigi.proofchain.custodyevent.domain.EventType;
import it.itsprodigi.proofchain.evidence.domain.AcquisitionMethod;
import it.itsprodigi.proofchain.evidence.domain.EvidenceStatus;
import it.itsprodigi.proofchain.evidence.domain.SourceType;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins the single reproducible genesis vector published in {@code docs/Custody-Events.md}. The documented
 * canonical byte string and its expected SHA-256 are asserted literally here, so the guide can never drift
 * away from the canonicalizer and the hash envelope actually implemented in production code.
 *
 * <p>The vector deliberately reuses the certified Sprint 3 hashes documented in {@code
 * docs/DigitalEvidence.md} for the exact content {@code ProofChain demo evidence} followed by one line feed.
 */
class CustodyEventDocumentationVectorTest {

    private static final UUID CASE_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID EVIDENCE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID EVENT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID OPERATOR_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final String CONTENT_SHA_256 = "9ac0e4751fa6d0fca5082060cd44e943660f510cc4af096424b6396d52327262";
    private static final String CONTEXTUAL_SHA_256 = "665dea9d0df23b5ea6e6a3b18f424270a3d9c5dd8e92e2272bfed580047a1b57";

    private static final String DOCUMENTED_DOMAIN_SEPARATOR = "proofchain:custody-event:v1\n";

    private static final String DOCUMENTED_PAYLOAD_JSON = """
            {"acquiredAt":null,"acquisitionLocation":null,"acquisitionMethod":"PHYSICAL","acquisitionNotes":null,\
            "acquisitionToolName":null,"acquisitionToolVersion":null,"backfilled":false,\
            "contentSha256":"9ac0e4751fa6d0fca5082060cd44e943660f510cc4af096424b6396d52327262",\
            "contextualSha256":"665dea9d0df23b5ea6e6a3b18f424270a3d9c5dd8e92e2272bfed580047a1b57",\
            "description":null,"fileExtension":"bin","fileSize":25,\
            "initialHolderId":"44444444-4444-4444-8444-444444444444",\
            "mediaType":"application/octet-stream","originalFilename":"demo-evidence.bin","referenceTag":"DEMO-01",\
            "sourceDescription":null,"sourceLogicalIdentifier":null,"sourceManufacturer":null,"sourceModel":null,\
            "sourceSerialNumber":null,"sourceType":"DEVICE","status":"IN_CUSTODY","title":"Forensic demo evidence",\
            "uploadedById":"44444444-4444-4444-8444-444444444444"}\
            """;

    private static final String DOCUMENTED_CANONICAL_JSON = "{\"actorRole\":\"EVIDENCE_OFFICER\","
            + "\"caseId\":\"11111111-1111-4111-8111-111111111111\","
            + "\"eventId\":\"33333333-3333-4333-8333-333333333333\","
            + "\"eventType\":\"EVIDENCE_REGISTERED\","
            + "\"evidenceId\":\"22222222-2222-4222-8222-222222222222\","
            + "\"occurredAt\":\"2026-07-30T10:15:30.123456Z\","
            + "\"operatorId\":\"44444444-4444-4444-8444-444444444444\","
            + "\"payload\":"
            + DOCUMENTED_PAYLOAD_JSON
            + ",\"payloadVersion\":1,"
            + "\"previousHash\":\"0000000000000000000000000000000000000000000000000000000000000000\","
            + "\"sequenceNumber\":1}";

    private static final String DOCUMENTED_EVENT_HASH =
            "71bd5e38f56d4a22228532372d058304246ed58e8634b8e58da37fd30e82fd2d";

    @Test
    void publishedGenesisVectorMatchesTheCanonicalizerAndTheHashEnvelope() {
        CanonicalCustodyEvent event = documentedGenesisEvent();

        assertThat(CustodyEventCanonicalizer.canonicalizePayload(event.payload()))
                .isEqualTo(DOCUMENTED_PAYLOAD_JSON);
        assertThat(CustodyEventCanonicalizer.canonicalize(event)).isEqualTo(DOCUMENTED_CANONICAL_JSON);
        assertThat(CustodyEventCanonicalizer.canonicalBytes(event))
                .isEqualTo(DOCUMENTED_CANONICAL_JSON.getBytes(StandardCharsets.UTF_8));
        assertThat(event.previousHash()).isEqualTo(CustodyEventHashing.ZERO_HASH);
        assertThat(CustodyEventHashing.eventHash(event)).isEqualTo(DOCUMENTED_EVENT_HASH);
    }

    @Test
    void publishedGenesisVectorIsReproducibleFromTheDocumentedBytesAlone() {
        byte[] separator = DOCUMENTED_DOMAIN_SEPARATOR.getBytes(StandardCharsets.UTF_8);
        byte[] canonical = DOCUMENTED_CANONICAL_JSON.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream hashedInput = new ByteArrayOutputStream(separator.length + canonical.length);
        hashedInput.writeBytes(separator);
        hashedInput.writeBytes(canonical);

        String independentDigest;
        try {
            independentDigest = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(hashedInput.toByteArray()));
        } catch (Exception exception) {
            throw new AssertionError("SHA-256 must be available", exception);
        }

        assertThat(separator[separator.length - 1]).isEqualTo((byte) '\n');
        assertThat(canonical[canonical.length - 1]).isEqualTo((byte) '}');
        assertThat(independentDigest).isEqualTo(DOCUMENTED_EVENT_HASH);
        assertThat(independentDigest).isEqualTo(CustodyEventHashing.eventHash(documentedGenesisEvent()));
    }

    private static CanonicalCustodyEvent documentedGenesisEvent() {
        return new CanonicalCustodyEvent(
                EVENT_ID,
                CASE_ID,
                EVIDENCE_ID,
                OPERATOR_ID,
                OperatorRole.EVIDENCE_OFFICER,
                1L,
                EventType.EVIDENCE_REGISTERED,
                Instant.parse("2026-07-30T10:15:30.123456Z"),
                CanonicalCustodyEvent.PAYLOAD_VERSION,
                documentedPayload(),
                CustodyEventHashing.ZERO_HASH);
    }

    private static EvidenceRegisteredPayload documentedPayload() {
        return new EvidenceRegisteredPayload(
                false,
                "DEMO-01",
                "Forensic demo evidence",
                null,
                EvidenceStatus.IN_CUSTODY,
                SourceType.DEVICE,
                null,
                null,
                null,
                null,
                null,
                AcquisitionMethod.PHYSICAL,
                null,
                null,
                null,
                null,
                null,
                "demo-evidence.bin",
                "bin",
                "application/octet-stream",
                25L,
                CONTENT_SHA_256,
                CONTEXTUAL_SHA_256,
                OPERATOR_ID,
                OPERATOR_ID);
    }
}
