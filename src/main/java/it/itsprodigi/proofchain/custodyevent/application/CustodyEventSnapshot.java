package it.itsprodigi.proofchain.custodyevent.application;

import it.itsprodigi.proofchain.custodyevent.domain.EventType;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable snapshot of exactly what {@link CustodyChainVerifier} needs to verify one custody event.
 *
 * <p>Deliberately decoupled from the {@code CustodyEvent} JPA entity so that corruption scenarios which
 * production foreign keys (case/evidence pairing) prevent from ever being persisted, such as a mismatched
 * caseId or evidenceId, remain exercisable in unit tests constructed directly against this record.
 */
public record CustodyEventSnapshot(
        UUID eventId,
        UUID caseId,
        UUID evidenceId,
        UUID operatorId,
        OperatorRole actorRole,
        long sequenceNumber,
        EventType eventType,
        Instant occurredAt,
        int hashVersion,
        int payloadVersion,
        String payloadJson,
        String previousHash,
        String eventHash) {

    public CustodyEventSnapshot {
        eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        caseId = Objects.requireNonNull(caseId, "caseId must not be null");
        evidenceId = Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        operatorId = Objects.requireNonNull(operatorId, "operatorId must not be null");
        actorRole = Objects.requireNonNull(actorRole, "actorRole must not be null");
        eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        payloadJson = Objects.requireNonNull(payloadJson, "payloadJson must not be null");
        previousHash = Objects.requireNonNull(previousHash, "previousHash must not be null");
        eventHash = Objects.requireNonNull(eventHash, "eventHash must not be null");
    }
}
