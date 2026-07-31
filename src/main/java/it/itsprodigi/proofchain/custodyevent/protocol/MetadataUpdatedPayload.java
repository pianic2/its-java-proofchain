package it.itsprodigi.proofchain.custodyevent.protocol;

import it.itsprodigi.proofchain.custodyevent.domain.EventType;
import java.util.Objects;

public record MetadataUpdatedPayload(EvidenceMetadataSnapshot before, EvidenceMetadataSnapshot after, String reason)
        implements CustodyEventPayload {

    public MetadataUpdatedPayload {
        before = Objects.requireNonNull(before, "before must not be null");
        after = Objects.requireNonNull(after, "after must not be null");
        reason = ProtocolValidation.reason(reason);
    }

    @Override
    public EventType eventType() {
        return EventType.METADATA_UPDATED;
    }
}
