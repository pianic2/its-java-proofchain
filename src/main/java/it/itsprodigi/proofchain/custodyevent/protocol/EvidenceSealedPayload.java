package it.itsprodigi.proofchain.custodyevent.protocol;

import it.itsprodigi.proofchain.custodyevent.domain.EventType;
import it.itsprodigi.proofchain.evidence.domain.EvidenceStatus;
import java.util.Objects;
import java.util.UUID;

public record EvidenceSealedPayload(
        EvidenceStatus previousStatus, EvidenceStatus newStatus, UUID holderId, String reason)
        implements CustodyEventPayload {

    public EvidenceSealedPayload {
        if (previousStatus != EvidenceStatus.IN_CUSTODY) {
            throw new IllegalArgumentException("previousStatus must be IN_CUSTODY");
        }
        if (newStatus != EvidenceStatus.SEALED) {
            throw new IllegalArgumentException("newStatus must be SEALED");
        }
        holderId = Objects.requireNonNull(holderId, "holderId must not be null");
        reason = ProtocolValidation.reason(reason);
    }

    @Override
    public EventType eventType() {
        return EventType.EVIDENCE_SEALED;
    }
}
