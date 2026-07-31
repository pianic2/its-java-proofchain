package it.itsprodigi.proofchain.custodyevent.application;

import it.itsprodigi.proofchain.custodyevent.domain.EventType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CustodyEventAppendResult(
        UUID eventId,
        long sequenceNumber,
        EventType eventType,
        Instant occurredAt,
        String previousHash,
        String eventHash,
        int payloadVersion,
        int hashVersion) {

    public CustodyEventAppendResult {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(previousHash, "previousHash must not be null");
        Objects.requireNonNull(eventHash, "eventHash must not be null");
        if (sequenceNumber <= 0) {
            throw new IllegalArgumentException("sequenceNumber must be positive");
        }
        if (payloadVersion <= 0 || hashVersion <= 0) {
            throw new IllegalArgumentException("payloadVersion and hashVersion must be positive");
        }
    }
}
