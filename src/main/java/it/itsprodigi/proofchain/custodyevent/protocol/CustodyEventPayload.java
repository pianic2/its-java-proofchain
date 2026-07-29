package it.itsprodigi.proofchain.custodyevent.protocol;

import it.itsprodigi.proofchain.custodyevent.domain.EventType;

public sealed interface CustodyEventPayload
        permits EvidenceRegisteredPayload,
                CustodyTransferredPayload,
                MetadataUpdatedPayload,
                IntegrityVerifiedPayload,
                EvidenceSealedPayload,
                EvidenceReleasedPayload {

    EventType eventType();
}
