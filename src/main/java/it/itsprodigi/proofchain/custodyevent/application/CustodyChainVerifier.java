package it.itsprodigi.proofchain.custodyevent.application;

import it.itsprodigi.proofchain.custodyevent.protocol.CanonicalCustodyEvent;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventHashing;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventPayload;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Deterministic, side-effect-free custody-chain verifier.
 *
 * <p>Operates purely on {@link CustodyEventSnapshot} values, never on JPA entities, so that corruption
 * scenarios production foreign keys make unreachable in storage (a persisted event whose caseId or
 * evidenceId does not match its evidence) remain exercisable in unit tests. Never mutates its inputs, and
 * never throws for corrupt stored data: every diagnosable failure is reported through the returned {@link
 * CustodyChainVerificationResult} instead of escaping as an exception.
 *
 * <p>Failure reasons are evaluated in strict precedence order per event; evaluation stops at the first
 * violation found, so later events are never trusted once an earlier one has broken the chain.
 */
@Component
public final class CustodyChainVerifier {

    private static final int SUPPORTED_VERSION = 1;
    private static final String INVALID_PAYLOAD_MARKER = "invalid payload";

    private final CustodyEventPayloadCodec payloadCodec;

    public CustodyChainVerifier(CustodyEventPayloadCodec payloadCodec) {
        this.payloadCodec = Objects.requireNonNull(payloadCodec, "payloadCodec must not be null");
    }

    public CustodyChainVerificationResult verify(
            UUID evidenceId,
            UUID caseId,
            long storedEventCount,
            String storedHeadHash,
            List<CustodyEventSnapshot> events) {
        Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(storedHeadHash, "storedHeadHash must not be null");
        Objects.requireNonNull(events, "events must not be null");

        long loadedEventCount = events.size();
        if (events.isEmpty()) {
            return failure(
                    evidenceId,
                    CustodyChainVerificationReason.EMPTY_CHAIN,
                    0,
                    storedEventCount,
                    0,
                    storedHeadHash,
                    CustodyEventHashing.ZERO_HASH,
                    null,
                    null,
                    "at least one event",
                    "0 events");
        }
        if (loadedEventCount != storedEventCount) {
            return failure(
                    evidenceId,
                    CustodyChainVerificationReason.CHAIN_LENGTH_MISMATCH,
                    0,
                    storedEventCount,
                    loadedEventCount,
                    storedHeadHash,
                    CustodyEventHashing.ZERO_HASH,
                    null,
                    null,
                    String.valueOf(storedEventCount),
                    String.valueOf(loadedEventCount));
        }

        long expectedSequence = 1;
        String expectedPreviousHash = CustodyEventHashing.ZERO_HASH;
        String calculatedHeadHash = CustodyEventHashing.ZERO_HASH;
        long checkedEvents = 0;

        for (CustodyEventSnapshot event : events) {
            if (!event.caseId().equals(caseId)) {
                return failure(
                        evidenceId,
                        CustodyChainVerificationReason.CASE_MISMATCH,
                        checkedEvents,
                        storedEventCount,
                        loadedEventCount,
                        storedHeadHash,
                        calculatedHeadHash,
                        event.eventId(),
                        event.sequenceNumber(),
                        caseId.toString(),
                        event.caseId().toString());
            }
            if (!event.evidenceId().equals(evidenceId)) {
                return failure(
                        evidenceId,
                        CustodyChainVerificationReason.EVIDENCE_MISMATCH,
                        checkedEvents,
                        storedEventCount,
                        loadedEventCount,
                        storedHeadHash,
                        calculatedHeadHash,
                        event.eventId(),
                        event.sequenceNumber(),
                        evidenceId.toString(),
                        event.evidenceId().toString());
            }
            if (event.sequenceNumber() != expectedSequence) {
                return failure(
                        evidenceId,
                        CustodyChainVerificationReason.SEQUENCE_GAP,
                        checkedEvents,
                        storedEventCount,
                        loadedEventCount,
                        storedHeadHash,
                        calculatedHeadHash,
                        event.eventId(),
                        event.sequenceNumber(),
                        String.valueOf(expectedSequence),
                        String.valueOf(event.sequenceNumber()));
            }
            if (expectedSequence == 1) {
                if (!event.previousHash().equals(CustodyEventHashing.ZERO_HASH)) {
                    return failure(
                            evidenceId,
                            CustodyChainVerificationReason.GENESIS_MISMATCH,
                            checkedEvents,
                            storedEventCount,
                            loadedEventCount,
                            storedHeadHash,
                            calculatedHeadHash,
                            event.eventId(),
                            event.sequenceNumber(),
                            CustodyEventHashing.ZERO_HASH,
                            event.previousHash());
                }
            } else if (!event.previousHash().equals(expectedPreviousHash)) {
                return failure(
                        evidenceId,
                        CustodyChainVerificationReason.PREVIOUS_HASH_MISMATCH,
                        checkedEvents,
                        storedEventCount,
                        loadedEventCount,
                        storedHeadHash,
                        calculatedHeadHash,
                        event.eventId(),
                        event.sequenceNumber(),
                        expectedPreviousHash,
                        event.previousHash());
            }
            if (event.hashVersion() != SUPPORTED_VERSION) {
                return failure(
                        evidenceId,
                        CustodyChainVerificationReason.UNSUPPORTED_HASH_VERSION,
                        checkedEvents,
                        storedEventCount,
                        loadedEventCount,
                        storedHeadHash,
                        calculatedHeadHash,
                        event.eventId(),
                        event.sequenceNumber(),
                        String.valueOf(SUPPORTED_VERSION),
                        String.valueOf(event.hashVersion()));
            }
            if (event.payloadVersion() != SUPPORTED_VERSION) {
                return failure(
                        evidenceId,
                        CustodyChainVerificationReason.UNSUPPORTED_PAYLOAD_VERSION,
                        checkedEvents,
                        storedEventCount,
                        loadedEventCount,
                        storedHeadHash,
                        calculatedHeadHash,
                        event.eventId(),
                        event.sequenceNumber(),
                        String.valueOf(SUPPORTED_VERSION),
                        String.valueOf(event.payloadVersion()));
            }

            CustodyEventPayload payload;
            try {
                payload = payloadCodec.decode(
                        event.eventType(), event.payloadVersion(), event.hashVersion(), event.payloadJson());
            } catch (CustodyChainReadFailureException exception) {
                return invalidPayloadFailure(
                        evidenceId,
                        checkedEvents,
                        storedEventCount,
                        loadedEventCount,
                        storedHeadHash,
                        calculatedHeadHash,
                        event);
            }

            // The compact constructor of CanonicalCustodyEvent and the canonical-hash computation both
            // validate their input and throw IllegalArgumentException for anything structurally wrong
            // (bad UUID version, non-microsecond timestamp, unpaired surrogate, ...). Any such failure is
            // corrupt stored data, never a technical inability, so it is reported as INVALID_PAYLOAD.
            String recomputedHash;
            try {
                CanonicalCustodyEvent canonicalEvent = new CanonicalCustodyEvent(
                        event.eventId(),
                        event.caseId(),
                        event.evidenceId(),
                        event.operatorId(),
                        event.actorRole(),
                        event.sequenceNumber(),
                        event.eventType(),
                        event.occurredAt(),
                        event.payloadVersion(),
                        payload,
                        event.previousHash());
                recomputedHash = CustodyEventHashing.eventHash(canonicalEvent, event.hashVersion());
            } catch (IllegalArgumentException exception) {
                return invalidPayloadFailure(
                        evidenceId,
                        checkedEvents,
                        storedEventCount,
                        loadedEventCount,
                        storedHeadHash,
                        calculatedHeadHash,
                        event);
            }

            if (!recomputedHash.equals(event.eventHash())) {
                return failure(
                        evidenceId,
                        CustodyChainVerificationReason.EVENT_HASH_MISMATCH,
                        checkedEvents,
                        storedEventCount,
                        loadedEventCount,
                        storedHeadHash,
                        calculatedHeadHash,
                        event.eventId(),
                        event.sequenceNumber(),
                        recomputedHash,
                        event.eventHash());
            }

            checkedEvents++;
            expectedSequence++;
            expectedPreviousHash = recomputedHash;
            calculatedHeadHash = recomputedHash;
        }

        if (!calculatedHeadHash.equals(storedHeadHash)) {
            return failure(
                    evidenceId,
                    CustodyChainVerificationReason.CHAIN_HEAD_MISMATCH,
                    checkedEvents,
                    storedEventCount,
                    loadedEventCount,
                    storedHeadHash,
                    calculatedHeadHash,
                    null,
                    null,
                    storedHeadHash,
                    calculatedHeadHash);
        }

        return new CustodyChainVerificationResult(
                evidenceId,
                true,
                checkedEvents,
                storedEventCount,
                loadedEventCount,
                storedHeadHash,
                calculatedHeadHash,
                null,
                null,
                null,
                null,
                null);
    }

    private static CustodyChainVerificationResult invalidPayloadFailure(
            UUID evidenceId,
            long checkedEvents,
            long storedEventCount,
            long loadedEventCount,
            String storedHeadHash,
            String calculatedHeadHash,
            CustodyEventSnapshot event) {
        return failure(
                evidenceId,
                CustodyChainVerificationReason.INVALID_PAYLOAD,
                checkedEvents,
                storedEventCount,
                loadedEventCount,
                storedHeadHash,
                calculatedHeadHash,
                event.eventId(),
                event.sequenceNumber(),
                event.eventType().name(),
                INVALID_PAYLOAD_MARKER);
    }

    private static CustodyChainVerificationResult failure(
            UUID evidenceId,
            CustodyChainVerificationReason reason,
            long checkedEvents,
            long storedEventCount,
            long loadedEventCount,
            String storedHeadHash,
            String calculatedHeadHash,
            UUID brokenAtEventId,
            Long brokenAtSequenceNumber,
            String expectedValue,
            String actualValue) {
        return new CustodyChainVerificationResult(
                evidenceId,
                false,
                checkedEvents,
                storedEventCount,
                loadedEventCount,
                storedHeadHash,
                calculatedHeadHash,
                brokenAtEventId,
                brokenAtSequenceNumber,
                reason,
                expectedValue,
                actualValue);
    }
}
