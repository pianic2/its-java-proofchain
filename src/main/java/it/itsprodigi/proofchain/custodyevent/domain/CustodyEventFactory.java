package it.itsprodigi.proofchain.custodyevent.domain;

import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodyevent.protocol.CanonicalCustodyEvent;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventCanonicalizer;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventHashing;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.operator.domain.Operator;
import java.util.Objects;

public final class CustodyEventFactory {

    private CustodyEventFactory() {}

    public static CustodyEvent create(
            CanonicalCustodyEvent canonicalEvent,
            CustodyCase custodyCase,
            DigitalEvidence evidence,
            Operator operator,
            String eventHash) {
        Objects.requireNonNull(canonicalEvent, "canonicalEvent must not be null");
        Objects.requireNonNull(custodyCase, "custodyCase must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        Objects.requireNonNull(operator, "operator must not be null");
        if (!canonicalEvent.caseId().equals(custodyCase.getId())
                || !canonicalEvent.evidenceId().equals(evidence.getId())
                || !canonicalEvent.operatorId().equals(operator.getId())) {
            throw new IllegalArgumentException("canonical event identifiers must match aggregate references");
        }
        return CustodyEvent.createCanonical(
                canonicalEvent.eventId(),
                custodyCase,
                evidence,
                operator,
                canonicalEvent.actorRole(),
                canonicalEvent.sequenceNumber(),
                canonicalEvent.eventType(),
                canonicalEvent.occurredAt(),
                canonicalEvent.payloadVersion(),
                CustodyEventCanonicalizer.canonicalizePayload(canonicalEvent.payload()),
                canonicalEvent.previousHash(),
                eventHash,
                CustodyEventHashing.HASH_VERSION);
    }
}
