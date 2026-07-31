package it.itsprodigi.proofchain.evidence.application;

import it.itsprodigi.proofchain.custodyevent.api.CustodyEventSummaryResponse;
import it.itsprodigi.proofchain.custodyevent.application.CustodyEventAppendResult;
import it.itsprodigi.proofchain.evidence.api.EvidenceOperationResponse;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.operator.domain.Operator;
import java.net.URI;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Maps one mutated aggregate plus the appended custody event into the shared operational response contract.
 *
 * <p>Never exposes JPA entities, storage keys, chain-head fields or the internal optimistic version.
 */
@Component
public class EvidenceCommandResponseMapper {

    private static final String EVENT_LOCATION_TEMPLATE = "/api/v1/evidences/%s/events/%s";

    private final EvidenceMapper evidences;

    public EvidenceCommandResponseMapper(EvidenceMapper evidences) {
        this.evidences = Objects.requireNonNull(evidences, "evidences must not be null");
    }

    public EvidenceOperationResponse toResponse(
            DigitalEvidence evidence, Operator actor, CustodyEventAppendResult appended) {
        Objects.requireNonNull(evidence, "evidence must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(appended, "appended must not be null");
        return new EvidenceOperationResponse(evidences.toResponse(evidence), toSummary(evidence, actor, appended));
    }

    public URI location(EvidenceOperationResponse response) {
        Objects.requireNonNull(response, "response must not be null");
        CustodyEventSummaryResponse summary = response.eventSummary();
        return URI.create(EVENT_LOCATION_TEMPLATE.formatted(summary.evidenceId(), summary.id()));
    }

    private static CustodyEventSummaryResponse toSummary(
            DigitalEvidence evidence, Operator actor, CustodyEventAppendResult appended) {
        return new CustodyEventSummaryResponse(
                appended.eventId(),
                evidence.getCustodyCase().getId(),
                evidence.getId(),
                appended.sequenceNumber(),
                appended.eventType(),
                actor.getId(),
                actor.getRole(),
                appended.occurredAt(),
                appended.hashVersion(),
                appended.payloadVersion(),
                appended.previousHash(),
                appended.eventHash());
    }
}
