package it.itsprodigi.proofchain.custodyevent.application;

import it.itsprodigi.proofchain.custodyevent.api.CustodyEventDetailResponse;
import it.itsprodigi.proofchain.custodyevent.api.CustodyEventPageResponse;
import it.itsprodigi.proofchain.custodyevent.api.CustodyEventSummaryResponse;
import it.itsprodigi.proofchain.custodyevent.domain.CustodyEvent;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventPayload;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Component
public class CustodyEventMapper {

    private final CustodyEventPayloadCodec payloadCodec;

    public CustodyEventMapper(CustodyEventPayloadCodec payloadCodec) {
        this.payloadCodec = Objects.requireNonNull(payloadCodec, "payloadCodec must not be null");
    }

    public CustodyEventSummaryResponse toSummary(CustodyEvent event) {
        CustodyEvent value = Objects.requireNonNull(event, "event must not be null");
        decodePayload(value);
        return summary(value);
    }

    public CustodyEventDetailResponse toDetail(CustodyEvent event) {
        CustodyEvent value = Objects.requireNonNull(event, "event must not be null");
        CustodyEventPayload payload = decodePayload(value);
        return new CustodyEventDetailResponse(
                value.getId(),
                value.getCustodyCase().getId(),
                value.getEvidence().getId(),
                value.getSequenceNumber(),
                value.getEventType(),
                value.getOperator().getId(),
                value.getActorRole(),
                value.getOccurredAt(),
                value.getHashVersion(),
                value.getPayloadVersion(),
                value.getPreviousHash(),
                value.getEventHash(),
                payload);
    }

    public CustodyEventPageResponse toPage(Page<CustodyEvent> events) {
        Page<CustodyEvent> value = Objects.requireNonNull(events, "events must not be null");
        return new CustodyEventPageResponse(
                value.getContent().stream().map(this::toSummary).toList(),
                value.getNumber(),
                value.getSize(),
                value.getTotalElements(),
                value.getTotalPages());
    }

    private CustodyEventPayload decodePayload(CustodyEvent event) {
        return payloadCodec.decode(
                event.getEventType(), event.getPayloadVersion(), event.getHashVersion(), event.getPayloadJson());
    }

    private static CustodyEventSummaryResponse summary(CustodyEvent event) {
        return new CustodyEventSummaryResponse(
                event.getId(),
                event.getCustodyCase().getId(),
                event.getEvidence().getId(),
                event.getSequenceNumber(),
                event.getEventType(),
                event.getOperator().getId(),
                event.getActorRole(),
                event.getOccurredAt(),
                event.getHashVersion(),
                event.getPayloadVersion(),
                event.getPreviousHash(),
                event.getEventHash());
    }
}
