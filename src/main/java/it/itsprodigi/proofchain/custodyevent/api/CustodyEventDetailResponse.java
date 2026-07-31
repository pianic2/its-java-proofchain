package it.itsprodigi.proofchain.custodyevent.api;

import it.itsprodigi.proofchain.custodyevent.domain.EventType;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventPayload;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import java.time.Instant;
import java.util.UUID;

public record CustodyEventDetailResponse(
        UUID id,
        UUID caseId,
        UUID evidenceId,
        long sequenceNumber,
        EventType eventType,
        UUID operatorId,
        OperatorRole actorRole,
        Instant occurredAt,
        int hashVersion,
        int payloadVersion,
        String previousHash,
        String eventHash,
        CustodyEventPayload payload) {}
