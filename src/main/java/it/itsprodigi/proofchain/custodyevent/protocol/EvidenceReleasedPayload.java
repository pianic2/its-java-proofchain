package it.itsprodigi.proofchain.custodyevent.protocol;

import it.itsprodigi.proofchain.custodyevent.domain.EventType;
import it.itsprodigi.proofchain.evidence.domain.EvidenceStatus;
import java.util.Objects;
import java.util.UUID;

public record EvidenceReleasedPayload(
        EvidenceStatus previousStatus, EvidenceStatus newStatus, UUID previousHolderId, UUID newHolderId, String reason)
        implements CustodyEventPayload {

    public EvidenceReleasedPayload {
        if (previousStatus != EvidenceStatus.IN_CUSTODY && previousStatus != EvidenceStatus.SEALED) {
            throw new IllegalArgumentException("previousStatus must be IN_CUSTODY or SEALED");
        }
        if (newStatus != EvidenceStatus.RELEASED) {
            throw new IllegalArgumentException("newStatus must be RELEASED");
        }
        previousHolderId = Objects.requireNonNull(previousHolderId, "previousHolderId must not be null");
        if (newHolderId != null) {
            throw new IllegalArgumentException("newHolderId must be null");
        }
        reason = ProtocolValidation.reason(reason);
    }

    @Override
    public EventType eventType() {
        return EventType.EVIDENCE_RELEASED;
    }
}
