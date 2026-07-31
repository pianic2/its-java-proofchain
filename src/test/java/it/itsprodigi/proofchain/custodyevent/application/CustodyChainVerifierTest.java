package it.itsprodigi.proofchain.custodyevent.application;

import static org.assertj.core.api.Assertions.assertThat;

import it.itsprodigi.proofchain.custodyevent.domain.EventType;
import it.itsprodigi.proofchain.custodyevent.protocol.CanonicalCustodyEvent;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventCanonicalizer;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventHashing;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyTransferredPayload;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pure unit test for {@link CustodyChainVerifier}. Operates only on {@link CustodyEventSnapshot} values so
 * that corruptions production foreign keys make unreachable in storage (case/evidence mismatch) are still
 * exercisable here.
 */
class CustodyChainVerifierTest {

    private static final UUID EVIDENCE_ID = UUID.fromString("80000000-0000-4000-8000-000000000001");
    private static final UUID CASE_ID = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final UUID OPERATOR_ID = UUID.fromString("90000000-0000-4000-8000-000000000001");
    private static final UUID HOLDER_A = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final UUID HOLDER_B = UUID.fromString("b0000000-0000-4000-8000-000000000001");
    private static final Instant BASE_TIME = Instant.parse("2026-07-29T12:34:56.123456Z");
    private static final String ZERO_HASH = CustodyEventHashing.ZERO_HASH;

    private final CustodyChainVerifier verifier = new CustodyChainVerifier(new CustodyEventPayloadCodec(
            JsonMapper.builder().findAndAddModules().build()));

    @ParameterizedTest(name = "{0}")
    @MethodSource("verificationCases")
    void evaluatesExactReasonPrecedenceAndResponseSemantics(VerificationCase testCase) {
        CustodyChainVerificationResult result = verifier.verify(
                testCase.evidenceId(),
                testCase.caseId(),
                testCase.storedEventCount(),
                testCase.storedHeadHash(),
                testCase.events());

        assertThat(result.evidenceId()).isEqualTo(testCase.evidenceId());
        assertThat(result.valid()).isEqualTo(testCase.expectedValid());
        assertThat(result.checkedEvents()).isEqualTo(testCase.expectedCheckedEvents());
        assertThat(result.storedEventCount()).isEqualTo(testCase.storedEventCount());
        assertThat(result.loadedEventCount()).isEqualTo(testCase.events().size());
        assertThat(result.storedHeadHash()).isEqualTo(testCase.storedHeadHash());
        assertThat(result.calculatedHeadHash()).isEqualTo(testCase.expectedCalculatedHeadHash());
        assertThat(result.reason()).isEqualTo(testCase.expectedReason());
        assertThat(result.expectedValue()).isEqualTo(testCase.expectedExpectedValue());
        assertThat(result.actualValue()).isEqualTo(testCase.expectedActualValue());

        if (testCase.brokenEventIndex() < 0) {
            assertThat(result.brokenAtEventId()).isNull();
            assertThat(result.brokenAtSequenceNumber()).isNull();
        } else {
            CustodyEventSnapshot brokenEvent = testCase.events().get(testCase.brokenEventIndex());
            assertThat(result.brokenAtEventId()).isEqualTo(brokenEvent.eventId());
            assertThat(result.brokenAtSequenceNumber()).isEqualTo(brokenEvent.sequenceNumber());
        }
    }

    @Test
    void sequenceGapTakesPrecedenceOverEventHashMismatchWhenAnEventViolatesBothRules() {
        CustodyEventSnapshot genesis = buildValidChain(1).getFirst();
        CustodyEventSnapshot secondValid = snapshot(2, genesis.eventHash(), transferPayload(2));
        CustodyEventSnapshot corrupted =
                withEventHash(withSequenceNumber(secondValid, 5), flippedHash(secondValid.eventHash()));

        CustodyChainVerificationResult result =
                verifier.verify(EVIDENCE_ID, CASE_ID, 2, ZERO_HASH, List.of(genesis, corrupted));

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).isEqualTo(CustodyChainVerificationReason.SEQUENCE_GAP);
        assertThat(result.checkedEvents()).isEqualTo(1);
        assertThat(result.calculatedHeadHash()).isEqualTo(genesis.eventHash());
        assertThat(result.expectedValue()).isEqualTo("2");
        assertThat(result.actualValue()).isEqualTo("5");
        assertThat(result.brokenAtEventId()).isEqualTo(corrupted.eventId());
    }

    private static Stream<Arguments> verificationCases() {
        List<VerificationCase> cases = new ArrayList<>();

        cases.add(new VerificationCase(
                "EMPTY_CHAIN",
                EVIDENCE_ID,
                CASE_ID,
                0,
                ZERO_HASH,
                List.of(),
                false,
                0,
                ZERO_HASH,
                CustodyChainVerificationReason.EMPTY_CHAIN,
                "at least one event",
                "0 events",
                -1));

        List<CustodyEventSnapshot> lengthMismatchChain = buildValidChain(3);
        cases.add(new VerificationCase(
                "CHAIN_LENGTH_MISMATCH",
                EVIDENCE_ID,
                CASE_ID,
                4,
                lengthMismatchChain.getLast().eventHash(),
                lengthMismatchChain,
                false,
                0,
                ZERO_HASH,
                CustodyChainVerificationReason.CHAIN_LENGTH_MISMATCH,
                "4",
                "3",
                -1));

        List<CustodyEventSnapshot> caseMismatchChain = buildValidChain(1);
        UUID otherCaseId = UUID.fromString("70000000-0000-4000-8000-000000000099");
        cases.add(new VerificationCase(
                "CASE_MISMATCH",
                EVIDENCE_ID,
                otherCaseId,
                1,
                caseMismatchChain.getFirst().eventHash(),
                caseMismatchChain,
                false,
                0,
                ZERO_HASH,
                CustodyChainVerificationReason.CASE_MISMATCH,
                otherCaseId.toString(),
                CASE_ID.toString(),
                0));

        List<CustodyEventSnapshot> evidenceMismatchChain = buildValidChain(1);
        UUID otherEvidenceId = UUID.fromString("80000000-0000-4000-8000-000000000099");
        cases.add(new VerificationCase(
                "EVIDENCE_MISMATCH",
                otherEvidenceId,
                CASE_ID,
                1,
                evidenceMismatchChain.getFirst().eventHash(),
                evidenceMismatchChain,
                false,
                0,
                ZERO_HASH,
                CustodyChainVerificationReason.EVIDENCE_MISMATCH,
                otherEvidenceId.toString(),
                EVIDENCE_ID.toString(),
                0));

        CustodyEventSnapshot sequenceGapGenesis = buildValidChain(1).getFirst();
        CustodyEventSnapshot sequenceGapSecond = snapshot(3, sequenceGapGenesis.eventHash(), transferPayload(3));
        cases.add(new VerificationCase(
                "SEQUENCE_GAP",
                EVIDENCE_ID,
                CASE_ID,
                2,
                sequenceGapSecond.eventHash(),
                List.of(sequenceGapGenesis, sequenceGapSecond),
                false,
                1,
                sequenceGapGenesis.eventHash(),
                CustodyChainVerificationReason.SEQUENCE_GAP,
                "2",
                "3",
                1));

        CustodyEventSnapshot genesisMismatchEvent = snapshot(1, "a".repeat(64), transferPayload(1));
        cases.add(new VerificationCase(
                "GENESIS_MISMATCH",
                EVIDENCE_ID,
                CASE_ID,
                1,
                genesisMismatchEvent.eventHash(),
                List.of(genesisMismatchEvent),
                false,
                0,
                ZERO_HASH,
                CustodyChainVerificationReason.GENESIS_MISMATCH,
                ZERO_HASH,
                "a".repeat(64),
                0));

        CustodyEventSnapshot previousHashGenesis = buildValidChain(1).getFirst();
        String wrongPreviousHash = "b".repeat(64);
        CustodyEventSnapshot previousHashSecond = snapshot(2, wrongPreviousHash, transferPayload(2));
        cases.add(new VerificationCase(
                "PREVIOUS_HASH_MISMATCH",
                EVIDENCE_ID,
                CASE_ID,
                2,
                previousHashSecond.eventHash(),
                List.of(previousHashGenesis, previousHashSecond),
                false,
                1,
                previousHashGenesis.eventHash(),
                CustodyChainVerificationReason.PREVIOUS_HASH_MISMATCH,
                previousHashGenesis.eventHash(),
                wrongPreviousHash,
                1));

        CustodyEventSnapshot unsupportedHashVersionEvent =
                withHashVersion(buildValidChain(1).getFirst(), 2);
        cases.add(new VerificationCase(
                "UNSUPPORTED_HASH_VERSION",
                EVIDENCE_ID,
                CASE_ID,
                1,
                unsupportedHashVersionEvent.eventHash(),
                List.of(unsupportedHashVersionEvent),
                false,
                0,
                ZERO_HASH,
                CustodyChainVerificationReason.UNSUPPORTED_HASH_VERSION,
                "1",
                "2",
                0));

        CustodyEventSnapshot unsupportedPayloadVersionEvent =
                withPayloadVersion(buildValidChain(1).getFirst(), 2);
        cases.add(new VerificationCase(
                "UNSUPPORTED_PAYLOAD_VERSION",
                EVIDENCE_ID,
                CASE_ID,
                1,
                unsupportedPayloadVersionEvent.eventHash(),
                List.of(unsupportedPayloadVersionEvent),
                false,
                0,
                ZERO_HASH,
                CustodyChainVerificationReason.UNSUPPORTED_PAYLOAD_VERSION,
                "1",
                "2",
                0));

        CustodyEventSnapshot invalidPayloadEvent =
                withPayloadJson(buildValidChain(1).getFirst(), "{}");
        cases.add(new VerificationCase(
                "INVALID_PAYLOAD",
                EVIDENCE_ID,
                CASE_ID,
                1,
                invalidPayloadEvent.eventHash(),
                List.of(invalidPayloadEvent),
                false,
                0,
                ZERO_HASH,
                CustodyChainVerificationReason.INVALID_PAYLOAD,
                EventType.CUSTODY_TRANSFERRED.name(),
                "invalid payload",
                0));

        CustodyEventSnapshot eventHashMismatchGenesis = buildValidChain(1).getFirst();
        String wrongEventHash = flippedHash(eventHashMismatchGenesis.eventHash());
        CustodyEventSnapshot eventHashMismatchEvent = withEventHash(eventHashMismatchGenesis, wrongEventHash);
        cases.add(new VerificationCase(
                "EVENT_HASH_MISMATCH",
                EVIDENCE_ID,
                CASE_ID,
                1,
                wrongEventHash,
                List.of(eventHashMismatchEvent),
                false,
                0,
                ZERO_HASH,
                CustodyChainVerificationReason.EVENT_HASH_MISMATCH,
                eventHashMismatchGenesis.eventHash(),
                wrongEventHash,
                0));

        List<CustodyEventSnapshot> chainHeadMismatchChain = buildValidChain(2);
        String wrongHeadHash = flippedHash(chainHeadMismatchChain.getLast().eventHash());
        cases.add(new VerificationCase(
                "CHAIN_HEAD_MISMATCH",
                EVIDENCE_ID,
                CASE_ID,
                2,
                wrongHeadHash,
                chainHeadMismatchChain,
                false,
                2,
                chainHeadMismatchChain.getLast().eventHash(),
                CustodyChainVerificationReason.CHAIN_HEAD_MISMATCH,
                wrongHeadHash,
                chainHeadMismatchChain.getLast().eventHash(),
                -1));

        List<CustodyEventSnapshot> validGenesis = buildValidChain(1);
        cases.add(new VerificationCase(
                "VALID_GENESIS",
                EVIDENCE_ID,
                CASE_ID,
                1,
                validGenesis.getFirst().eventHash(),
                validGenesis,
                true,
                1,
                validGenesis.getFirst().eventHash(),
                null,
                null,
                null,
                -1));

        List<CustodyEventSnapshot> validMultiEvent = buildValidChain(4);
        cases.add(new VerificationCase(
                "VALID_MULTI_EVENT",
                EVIDENCE_ID,
                CASE_ID,
                4,
                validMultiEvent.getLast().eventHash(),
                validMultiEvent,
                true,
                4,
                validMultiEvent.getLast().eventHash(),
                null,
                null,
                null,
                -1));

        return cases.stream().map(testCase -> Arguments.of(testCase));
    }

    private static List<CustodyEventSnapshot> buildValidChain(int length) {
        List<CustodyEventSnapshot> chain = new ArrayList<>();
        String previousHash = ZERO_HASH;
        for (long sequenceNumber = 1; sequenceNumber <= length; sequenceNumber++) {
            CustodyEventSnapshot event = snapshot(sequenceNumber, previousHash, transferPayload(sequenceNumber));
            chain.add(event);
            previousHash = event.eventHash();
        }
        return chain;
    }

    private static CustodyEventSnapshot snapshot(
            long sequenceNumber, String previousHash, CustodyEventPayload payload) {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = BASE_TIME.plusSeconds(sequenceNumber);
        CanonicalCustodyEvent canonical = new CanonicalCustodyEvent(
                eventId,
                CASE_ID,
                EVIDENCE_ID,
                OPERATOR_ID,
                OperatorRole.EVIDENCE_OFFICER,
                sequenceNumber,
                payload.eventType(),
                occurredAt,
                CanonicalCustodyEvent.PAYLOAD_VERSION,
                payload,
                previousHash);
        String eventHash = CustodyEventHashing.eventHash(canonical);
        return new CustodyEventSnapshot(
                eventId,
                CASE_ID,
                EVIDENCE_ID,
                OPERATOR_ID,
                OperatorRole.EVIDENCE_OFFICER,
                sequenceNumber,
                payload.eventType(),
                occurredAt,
                CustodyEventHashing.HASH_VERSION,
                CanonicalCustodyEvent.PAYLOAD_VERSION,
                CustodyEventCanonicalizer.canonicalizePayload(payload),
                previousHash,
                eventHash);
    }

    private static CustodyEventPayload transferPayload(long sequenceNumber) {
        return sequenceNumber % 2 == 1
                ? new CustodyTransferredPayload(HOLDER_A, HOLDER_B, "Transfer " + sequenceNumber)
                : new CustodyTransferredPayload(HOLDER_B, HOLDER_A, "Transfer " + sequenceNumber);
    }

    private static String flippedHash(String hash) {
        char first = hash.charAt(0);
        char replacement = first == '0' ? '1' : '0';
        return replacement + hash.substring(1);
    }

    private static CustodyEventSnapshot withHashVersion(CustodyEventSnapshot event, int hashVersion) {
        return new CustodyEventSnapshot(
                event.eventId(),
                event.caseId(),
                event.evidenceId(),
                event.operatorId(),
                event.actorRole(),
                event.sequenceNumber(),
                event.eventType(),
                event.occurredAt(),
                hashVersion,
                event.payloadVersion(),
                event.payloadJson(),
                event.previousHash(),
                event.eventHash());
    }

    private static CustodyEventSnapshot withPayloadVersion(CustodyEventSnapshot event, int payloadVersion) {
        return new CustodyEventSnapshot(
                event.eventId(),
                event.caseId(),
                event.evidenceId(),
                event.operatorId(),
                event.actorRole(),
                event.sequenceNumber(),
                event.eventType(),
                event.occurredAt(),
                event.hashVersion(),
                payloadVersion,
                event.payloadJson(),
                event.previousHash(),
                event.eventHash());
    }

    private static CustodyEventSnapshot withPayloadJson(CustodyEventSnapshot event, String payloadJson) {
        return new CustodyEventSnapshot(
                event.eventId(),
                event.caseId(),
                event.evidenceId(),
                event.operatorId(),
                event.actorRole(),
                event.sequenceNumber(),
                event.eventType(),
                event.occurredAt(),
                event.hashVersion(),
                event.payloadVersion(),
                payloadJson,
                event.previousHash(),
                event.eventHash());
    }

    private static CustodyEventSnapshot withEventHash(CustodyEventSnapshot event, String eventHash) {
        return new CustodyEventSnapshot(
                event.eventId(),
                event.caseId(),
                event.evidenceId(),
                event.operatorId(),
                event.actorRole(),
                event.sequenceNumber(),
                event.eventType(),
                event.occurredAt(),
                event.hashVersion(),
                event.payloadVersion(),
                event.payloadJson(),
                event.previousHash(),
                eventHash);
    }

    private static CustodyEventSnapshot withSequenceNumber(CustodyEventSnapshot event, long sequenceNumber) {
        return new CustodyEventSnapshot(
                event.eventId(),
                event.caseId(),
                event.evidenceId(),
                event.operatorId(),
                event.actorRole(),
                sequenceNumber,
                event.eventType(),
                event.occurredAt(),
                event.hashVersion(),
                event.payloadVersion(),
                event.payloadJson(),
                event.previousHash(),
                event.eventHash());
    }

    private record VerificationCase(
            String name,
            UUID evidenceId,
            UUID caseId,
            long storedEventCount,
            String storedHeadHash,
            List<CustodyEventSnapshot> events,
            boolean expectedValid,
            long expectedCheckedEvents,
            String expectedCalculatedHeadHash,
            CustodyChainVerificationReason expectedReason,
            String expectedExpectedValue,
            String expectedActualValue,
            int brokenEventIndex) {

        @Override
        public String toString() {
            return name;
        }
    }
}
