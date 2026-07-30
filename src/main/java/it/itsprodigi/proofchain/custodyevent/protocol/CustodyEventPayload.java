package it.itsprodigi.proofchain.custodyevent.protocol;

import io.swagger.v3.oas.annotations.media.Schema;
import it.itsprodigi.proofchain.custodyevent.domain.EventType;

@Schema(
        description = "Frozen typed custody-event payload selected by eventType and payloadVersion.",
        oneOf = {
            EvidenceRegisteredPayload.class,
            CustodyTransferredPayload.class,
            MetadataUpdatedPayload.class,
            IntegrityVerifiedPayload.class,
            EvidenceSealedPayload.class,
            EvidenceReleasedPayload.class
        })
public sealed interface CustodyEventPayload
        permits EvidenceRegisteredPayload,
                CustodyTransferredPayload,
                MetadataUpdatedPayload,
                IntegrityVerifiedPayload,
                EvidenceSealedPayload,
                EvidenceReleasedPayload {

    EventType eventType();
}
