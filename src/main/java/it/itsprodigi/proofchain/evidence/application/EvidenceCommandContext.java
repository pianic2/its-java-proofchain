package it.itsprodigi.proofchain.evidence.application;

import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.operator.domain.Operator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Trusted state handed to a workflow body once both locks are held, authorization has been re-evaluated and the single
 * command instant has been generated.
 */
public record EvidenceCommandContext(
        EvidenceOperationalCommand command,
        CustodyCase custodyCase,
        DigitalEvidence evidence,
        Operator actor,
        Instant occurredAt) {

    public EvidenceCommandContext {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(custodyCase, "custodyCase must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (!occurredAt.equals(occurredAt.truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalArgumentException("occurredAt must have microsecond precision");
        }
    }
}
