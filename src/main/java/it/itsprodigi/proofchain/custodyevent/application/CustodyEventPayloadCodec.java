package it.itsprodigi.proofchain.custodyevent.application;

import it.itsprodigi.proofchain.custodyevent.domain.EventType;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventCanonicalizer;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyTransferredPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.EvidenceRegisteredPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.EvidenceReleasedPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.EvidenceSealedPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.IntegrityVerifiedPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.MetadataUpdatedPayload;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class CustodyEventPayloadCodec {

    private static final int SUPPORTED_VERSION = 1;

    private final JsonMapper json;

    public CustodyEventPayloadCodec(JsonMapper json) {
        this.json = Objects.requireNonNull(json, "json must not be null");
    }

    public CustodyEventPayload decode(EventType eventType, int payloadVersion, int hashVersion, String payloadJson) {
        try {
            if (payloadVersion != SUPPORTED_VERSION || hashVersion != SUPPORTED_VERSION) {
                throw new CustodyChainReadFailureException();
            }
            Class<? extends CustodyEventPayload> payloadType =
                    switch (Objects.requireNonNull(eventType)) {
                        case EVIDENCE_REGISTERED -> EvidenceRegisteredPayload.class;
                        case CUSTODY_TRANSFERRED -> CustodyTransferredPayload.class;
                        case METADATA_UPDATED -> MetadataUpdatedPayload.class;
                        case INTEGRITY_VERIFIED -> IntegrityVerifiedPayload.class;
                        case EVIDENCE_SEALED -> EvidenceSealedPayload.class;
                        case EVIDENCE_RELEASED -> EvidenceReleasedPayload.class;
                    };
            String storedJson = Objects.requireNonNull(payloadJson);
            JsonNode storedPayload = json.readTree(storedJson);
            CustodyEventPayload payload = json.readValue(storedJson, payloadType);
            JsonNode exactPayload = json.readTree(CustodyEventCanonicalizer.canonicalizePayload(payload));
            if (storedPayload == null
                    || !storedPayload.isObject()
                    || !storedPayload.equals(exactPayload)
                    || payload.eventType() != eventType) {
                throw new CustodyChainReadFailureException();
            }
            return payload;
        } catch (CustodyChainReadFailureException exception) {
            throw exception;
        } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
            throw new CustodyChainReadFailureException(exception);
        }
    }
}
