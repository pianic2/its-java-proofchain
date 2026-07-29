package it.itsprodigi.proofchain.custodyevent.application;

import it.itsprodigi.proofchain.custodyevent.domain.CustodyEvent;
import it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFactory;
import it.itsprodigi.proofchain.custodyevent.persistence.CustodyEventRepository;
import it.itsprodigi.proofchain.custodyevent.protocol.CanonicalCustodyEvent;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventHashing;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventPayload;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceRepository;
import it.itsprodigi.proofchain.operator.domain.Operator;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustodyEventAppender {

    private final DigitalEvidenceRepository evidences;
    private final CustodyEventRepository events;
    private final EntityManager entityManager;
    private final Clock clock;

    public CustodyEventAppender(
            DigitalEvidenceRepository evidences,
            CustodyEventRepository events,
            EntityManager entityManager,
            Clock clock) {
        this.evidences = Objects.requireNonNull(evidences, "evidences must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CustodyEventAppendResult append(UUID evidenceId, Operator actor, CustodyEventPayload payload) {
        Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        try {
            DigitalEvidence evidence = evidences
                    .findByIdForCustodyEventAppend(evidenceId)
                    .orElseThrow(() -> new IllegalArgumentException("evidence does not exist"));
            return appendManaged(evidence, actor, payload, nowAtMicrosecondPrecision());
        } catch (PessimisticLockingFailureException | OptimisticLockingFailureException exception) {
            throw new CustodyEventConcurrencyConflictException(exception);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CustodyEventAppendResult initializeGenesis(
            DigitalEvidence managedEvidence, Operator actor, CustodyEventPayload payload, Instant occurredAt) {
        Objects.requireNonNull(managedEvidence, "managedEvidence must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (!entityManager.contains(managedEvidence)) {
            throw new IllegalArgumentException("managedEvidence must be managed in the current transaction");
        }
        if (managedEvidence.getCustodyEventCount() != 0
                || !CustodyEventHashing.ZERO_HASH.equals(managedEvidence.getCustodyChainHeadHash())
                || events.existsByEvidenceId(managedEvidence.getId())) {
            throw new IllegalStateException("genesis requires an empty custody chain");
        }
        if (!managedEvidence.getCreatedAt().equals(occurredAt)) {
            throw new IllegalArgumentException("genesis occurredAt must equal evidence createdAt");
        }
        return appendManaged(managedEvidence, actor, payload, occurredAt);
    }

    private CustodyEventAppendResult appendManaged(
            DigitalEvidence evidence, Operator actor, CustodyEventPayload payload, Instant occurredAt) {
        long sequenceNumber = Math.addExact(evidence.getCustodyEventCount(), 1L);
        String previousHash = sequenceNumber == 1 ? CustodyEventHashing.ZERO_HASH : evidence.getCustodyChainHeadHash();
        UUID eventId = UUID.randomUUID();
        CanonicalCustodyEvent canonicalEvent = new CanonicalCustodyEvent(
                eventId,
                evidence.getCustodyCase().getId(),
                evidence.getId(),
                actor.getId(),
                actor.getRole(),
                sequenceNumber,
                payload.eventType(),
                occurredAt,
                CanonicalCustodyEvent.PAYLOAD_VERSION,
                payload,
                previousHash);
        String eventHash = CustodyEventHashing.eventHash(canonicalEvent);
        CustodyEvent event =
                CustodyEventFactory.create(canonicalEvent, evidence.getCustodyCase(), evidence, actor, eventHash);
        events.saveAndFlush(event);
        evidence.advanceCustodyChain(sequenceNumber, eventHash);
        return new CustodyEventAppendResult(eventId, sequenceNumber, payload.eventType(), occurredAt, eventHash);
    }

    private Instant nowAtMicrosecondPrecision() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
