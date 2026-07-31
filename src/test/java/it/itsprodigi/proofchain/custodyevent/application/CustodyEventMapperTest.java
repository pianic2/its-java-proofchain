package it.itsprodigi.proofchain.custodyevent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodyevent.api.CustodyEventDetailResponse;
import it.itsprodigi.proofchain.custodyevent.api.CustodyEventSummaryResponse;
import it.itsprodigi.proofchain.custodyevent.domain.CustodyEvent;
import it.itsprodigi.proofchain.custodyevent.domain.EventType;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventCanonicalizer;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyTransferredPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.EvidenceMetadataSnapshot;
import it.itsprodigi.proofchain.custodyevent.protocol.EvidenceRegisteredPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.EvidenceReleasedPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.EvidenceSealedPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.IntegrityVerifiedPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.MetadataUpdatedPayload;
import it.itsprodigi.proofchain.evidence.domain.AcquisitionMethod;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.domain.EvidenceStatus;
import it.itsprodigi.proofchain.evidence.domain.SourceType;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

class CustodyEventMapperTest {

    private static final UUID EVENT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID CASE_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID EVIDENCE_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID OPERATOR_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID HOLDER_ONE = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID HOLDER_TWO = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final Instant OCCURRED_AT = Instant.parse("2026-07-30T08:09:10.123456Z");
    private static final String PREVIOUS_HASH = "a".repeat(64);
    private static final String EVENT_HASH = "b".repeat(64);

    private final CustodyEventPayloadCodec codec = new CustodyEventPayloadCodec(
            JsonMapper.builder().findAndAddModules().build());
    private final CustodyEventMapper mapper = new CustodyEventMapper(codec);

    @ParameterizedTest(name = "{0}")
    @MethodSource("supportedPayloads")
    void decodesAndMapsEveryV1PayloadUsingThePersistedActorRole(
            EventType eventType, CustodyEventPayload expectedPayload) {
        CustodyCase custodyCase = mock(CustodyCase.class);
        DigitalEvidence evidence = mock(DigitalEvidence.class);
        Operator operator = mock(Operator.class);
        CustodyEvent event = mock(CustodyEvent.class);
        when(custodyCase.getId()).thenReturn(CASE_ID);
        when(evidence.getId()).thenReturn(EVIDENCE_ID);
        when(operator.getId()).thenReturn(OPERATOR_ID);
        when(event.getId()).thenReturn(EVENT_ID);
        when(event.getCustodyCase()).thenReturn(custodyCase);
        when(event.getEvidence()).thenReturn(evidence);
        when(event.getOperator()).thenReturn(operator);
        when(event.getActorRole()).thenReturn(OperatorRole.AUDITOR);
        when(event.getSequenceNumber()).thenReturn(7L);
        when(event.getEventType()).thenReturn(eventType);
        when(event.getOccurredAt()).thenReturn(OCCURRED_AT);
        when(event.getHashVersion()).thenReturn(1);
        when(event.getPayloadVersion()).thenReturn(1);
        when(event.getPayloadJson()).thenReturn(CustodyEventCanonicalizer.canonicalizePayload(expectedPayload));
        when(event.getPreviousHash()).thenReturn(PREVIOUS_HASH);
        when(event.getEventHash()).thenReturn(EVENT_HASH);

        CustodyEventSummaryResponse summary = mapper.toSummary(event);
        CustodyEventDetailResponse detail = mapper.toDetail(event);

        assertThat(summary)
                .isEqualTo(new CustodyEventSummaryResponse(
                        EVENT_ID,
                        CASE_ID,
                        EVIDENCE_ID,
                        7,
                        eventType,
                        OPERATOR_ID,
                        OperatorRole.AUDITOR,
                        OCCURRED_AT,
                        1,
                        1,
                        PREVIOUS_HASH,
                        EVENT_HASH));
        assertThat(detail.payload()).isEqualTo(expectedPayload);
        assertThat(detail.actorRole()).isEqualTo(OperatorRole.AUDITOR);
        verify(operator, never()).getRole();
    }

    @ParameterizedTest
    @CsvSource({"2, 1", "1, 2", "0, 1", "1, 0"})
    void rejectsUnsupportedPayloadOrHashVersions(int payloadVersion, int hashVersion) {
        assertThatThrownBy(() -> codec.decode(EventType.INTEGRITY_VERIFIED, payloadVersion, hashVersion, "{}"))
                .isInstanceOf(CustodyChainReadFailureException.class)
                .hasMessage("Custody chain data could not be read safely.");
    }

    @Test
    void rejectsMalformedJsonAndPayloadShapeWithoutExposingParserDetails() {
        assertSafeReadFailure(() -> codec.decode(EventType.CUSTODY_TRANSFERRED, 1, 1, "{not-json}"));
        assertSafeReadFailure(() -> codec.decode(EventType.CUSTODY_TRANSFERRED, 1, 1, "{}"));
        assertSafeReadFailure(() -> codec.decode(null, 1, 1, "{}"));
    }

    private static void assertSafeReadFailure(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(CustodyChainReadFailureException.class)
                .hasMessage("Custody chain data could not be read safely.");
    }

    private static Stream<Arguments> supportedPayloads() {
        EvidenceMetadataSnapshot before = metadata("Before title", SourceType.DEVICE);
        EvidenceMetadataSnapshot after = metadata("After title", SourceType.FILESYSTEM);
        return Stream.of(
                Arguments.of(EventType.EVIDENCE_REGISTERED, registered()),
                Arguments.of(
                        EventType.CUSTODY_TRANSFERRED,
                        new CustodyTransferredPayload(HOLDER_ONE, HOLDER_TWO, "Approved transfer")),
                Arguments.of(
                        EventType.METADATA_UPDATED, new MetadataUpdatedPayload(before, after, "Corrected metadata")),
                Arguments.of(
                        EventType.INTEGRITY_VERIFIED,
                        new IntegrityVerifiedPayload(
                                IntegrityVerifiedPayload.SHA_256, EVENT_HASH, EVENT_HASH, true, 4096)),
                Arguments.of(
                        EventType.EVIDENCE_SEALED,
                        new EvidenceSealedPayload(
                                EvidenceStatus.IN_CUSTODY, EvidenceStatus.SEALED, HOLDER_ONE, "Case sealed")),
                Arguments.of(
                        EventType.EVIDENCE_RELEASED,
                        new EvidenceReleasedPayload(
                                EvidenceStatus.SEALED,
                                EvidenceStatus.RELEASED,
                                HOLDER_ONE,
                                null,
                                "Authorized release")));
    }

    private static EvidenceRegisteredPayload registered() {
        return new EvidenceRegisteredPayload(
                false,
                "EVIDENCE-01",
                "Registered evidence",
                "Initial registration",
                EvidenceStatus.IN_CUSTODY,
                SourceType.DEVICE,
                "Laptop",
                "Acme",
                "Model One",
                "SERIAL-01",
                "/dev/disk1",
                AcquisitionMethod.PHYSICAL,
                OCCURRED_AT.minusSeconds(60),
                "Lab",
                "Imager",
                "1.0",
                "Write blocker used",
                "disk.E01",
                "e01",
                "application/octet-stream",
                4096,
                PREVIOUS_HASH,
                EVENT_HASH,
                OPERATOR_ID,
                HOLDER_ONE);
    }

    private static EvidenceMetadataSnapshot metadata(String title, SourceType sourceType) {
        return new EvidenceMetadataSnapshot(
                title,
                "Description",
                sourceType,
                "Source",
                "Acme",
                "Model",
                "Serial",
                "/dev/disk1",
                AcquisitionMethod.LOGICAL,
                OCCURRED_AT.minusSeconds(120),
                "Lab",
                "Tool",
                "1.0",
                "Notes");
    }
}
