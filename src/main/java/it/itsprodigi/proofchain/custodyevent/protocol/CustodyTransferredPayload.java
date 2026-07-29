package it.itsprodigi.proofchain.custodyevent.protocol;

import it.itsprodigi.proofchain.custodyevent.domain.EventType;
import java.util.Objects;
import java.util.UUID;

public record CustodyTransferredPayload(UUID previousHolderId, UUID newHolderId, String reason)
        implements CustodyEventPayload {

    public CustodyTransferredPayload {
        previousHolderId = Objects.requireNonNull(previousHolderId, "previousHolderId must not be null");
        newHolderId = Objects.requireNonNull(newHolderId, "newHolderId must not be null");
        if (previousHolderId.equals(newHolderId)) {
            throw new IllegalArgumentException("newHolderId must differ from previousHolderId");
        }
        reason = ProtocolValidation.reason(reason);
    }

    @Override
    public EventType eventType() {
        return EventType.CUSTODY_TRANSFERRED;
    }
}
