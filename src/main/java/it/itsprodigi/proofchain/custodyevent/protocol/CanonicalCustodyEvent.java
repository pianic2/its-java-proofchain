package it.itsprodigi.proofchain.custodyevent.protocol;

import it.itsprodigi.proofchain.custodyevent.domain.EventType;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CanonicalCustodyEvent(
        UUID eventId,
        UUID caseId,
        UUID evidenceId,
        UUID operatorId,
        OperatorRole actorRole,
        long sequenceNumber,
        EventType eventType,
        Instant occurredAt,
        int payloadVersion,
        CustodyEventPayload payload,
        String previousHash) {

    public static final int PAYLOAD_VERSION = 1;

    public CanonicalCustodyEvent {
        eventId = ProtocolValidation.uuidV4(eventId, "eventId");
        caseId = Objects.requireNonNull(caseId, "caseId must not be null");
        evidenceId = Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        operatorId = Objects.requireNonNull(operatorId, "operatorId must not be null");
        actorRole = Objects.requireNonNull(actorRole, "actorRole must not be null");
        sequenceNumber = ProtocolValidation.positive(sequenceNumber, "sequenceNumber");
        eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        occurredAt = ProtocolValidation.microsecondInstant(occurredAt, "occurredAt");
        if (payloadVersion != PAYLOAD_VERSION) {
            throw new IllegalArgumentException("payloadVersion must be 1");
        }
        payload = Objects.requireNonNull(payload, "payload must not be null");
        if (payload.eventType() != eventType) {
            throw new IllegalArgumentException("payload must match eventType");
        }
        previousHash = ProtocolValidation.sha256(previousHash, "previousHash");
    }
}
