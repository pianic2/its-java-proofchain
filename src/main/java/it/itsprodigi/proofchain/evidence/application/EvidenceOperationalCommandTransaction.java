package it.itsprodigi.proofchain.evidence.application;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.custodycase.application.CaseClosedException;
import it.itsprodigi.proofchain.custodycase.domain.CaseStatus;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodyevent.application.CustodyEventAppendResult;
import it.itsprodigi.proofchain.custodyevent.application.CustodyEventAppender;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventPayload;
import it.itsprodigi.proofchain.evidence.api.EvidenceOperationResponse;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.domain.EvidenceStatus;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceRepository;
import it.itsprodigi.proofchain.operator.domain.Operator;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shared transaction template for every Sprint 5 operational custody command.
 *
 * <p>The order is frozen: visibility-aware lookup, {@code PESSIMISTIC_READ} custody case, re-check of visibility, role,
 * membership and case status, {@code PESSIMISTIC_WRITE} evidence, shared lifecycle and holder re-checks, one server UTC
 * instant truncated to microseconds, workflow mutation and payload, custody event append, flush, detached mapping.
 *
 * <p>A failed append rolls back the aggregate mutation because everything runs in this single transaction.
 */
@Service
public class EvidenceOperationalCommandTransaction {

    private final EvidenceOperationalAccessService access;
    private final EvidenceCommandLockService locks;
    private final CustodyEventAppender custodyEvents;
    private final DigitalEvidenceRepository evidences;
    private final EvidenceCommandResponseMapper responses;
    private final Clock clock;

    public EvidenceOperationalCommandTransaction(
            EvidenceOperationalAccessService access,
            EvidenceCommandLockService locks,
            CustodyEventAppender custodyEvents,
            DigitalEvidenceRepository evidences,
            EvidenceCommandResponseMapper responses,
            Clock clock) {
        this.access = Objects.requireNonNull(access, "access must not be null");
        this.locks = Objects.requireNonNull(locks, "locks must not be null");
        this.custodyEvents = Objects.requireNonNull(custodyEvents, "custodyEvents must not be null");
        this.evidences = Objects.requireNonNull(evidences, "evidences must not be null");
        this.responses = Objects.requireNonNull(responses, "responses must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public EvidenceOperationResponse execute(
            EvidenceOperationalCommand command,
            UUID evidenceId,
            AuthenticatedOperator actor,
            EvidenceCommandBody body) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(body, "body must not be null");

        UUID caseId = access.requireVisibleCaseId(evidenceId, actor);
        CaseReadLock caseLock = locks.lockCase(caseId);
        CustodyCase custodyCase = caseLock.custodyCase();

        Operator currentActor = access.requireAuthorizedActor(command, caseId, actor);
        if (custodyCase.getStatus() != CaseStatus.OPEN) {
            throw new CaseClosedException();
        }

        DigitalEvidence evidence = locks.lockEvidence(caseLock, evidenceId);
        access.requireAuthorizedHolder(command, currentActor, evidence);
        requireCommandableLifecycle(command, evidence);

        Instant occurredAt = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        CustodyEventPayload payload =
                body.apply(new EvidenceCommandContext(command, custodyCase, evidence, currentActor, occurredAt));
        Objects.requireNonNull(payload, "payload must not be null");
        if (command.mutating()) {
            evidence.stampCommandInstant(occurredAt);
        }

        CustodyEventAppendResult appended = custodyEvents.append(evidenceId, currentActor, payload, occurredAt);
        evidences.saveAndFlush(evidence);
        return responses.toResponse(evidence, currentActor, appended);
    }

    private static void requireCommandableLifecycle(EvidenceOperationalCommand command, DigitalEvidence evidence) {
        if (command.mutating() && evidence.getStatus() == EvidenceStatus.RELEASED) {
            throw new InvalidEvidenceStateException("Released evidence is terminal and cannot be modified.");
        }
    }
}
