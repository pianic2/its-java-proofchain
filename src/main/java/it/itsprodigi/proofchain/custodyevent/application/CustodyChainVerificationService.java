package it.itsprodigi.proofchain.custodyevent.application;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.common.exception.ResourceNotFoundException;
import it.itsprodigi.proofchain.custodycase.application.CaseAccessService;
import it.itsprodigi.proofchain.custodyevent.api.CustodyChainVerificationResponse;
import it.itsprodigi.proofchain.custodyevent.domain.CustodyEvent;
import it.itsprodigi.proofchain.custodyevent.persistence.CustodyEventRepository;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic, read-only full-chain verification for one {@link DigitalEvidence}. Never mutates the
 * evidence, its custody events, or the custody case; a single {@code PESSIMISTIC_READ} lock is acquired on
 * the evidence row so that verification observes a coherent before- or after-append snapshot, never a
 * mixed one.
 */
@Service
public class CustodyChainVerificationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CustodyChainVerificationService.class);

    private final DigitalEvidenceRepository evidences;
    private final CustodyEventRepository events;
    private final CaseAccessService access;
    private final CustodyChainVerifier verifier;
    private final EntityManager entityManager;
    private final Clock clock;

    public CustodyChainVerificationService(
            DigitalEvidenceRepository evidences,
            CustodyEventRepository events,
            CaseAccessService access,
            CustodyChainVerifier verifier,
            EntityManager entityManager,
            Clock clock) {
        this.evidences = Objects.requireNonNull(evidences, "evidences must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.access = Objects.requireNonNull(access, "access must not be null");
        this.verifier = Objects.requireNonNull(verifier, "verifier must not be null");
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    // Intentionally not readOnly = true: Spring's JpaTransactionManager marks a readOnly transaction's JDBC
    // connection read-only, and PostgreSQL (via pgjdbc's default readOnlyMode=transaction) then rejects
    // "SELECT ... FOR SHARE" with "cannot execute SELECT FOR SHARE in a read-only transaction" -- so a
    // PESSIMISTIC_READ lock is not executable inside a literally read-only transaction on this stack. This
    // method still performs zero writes; only the read-only JDBC hint is dropped to keep the required lock.
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public CustodyChainVerificationResponse verifyChain(UUID evidenceId, AuthenticatedOperator actor) {
        Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");

        DigitalEvidence visibleEvidence =
                evidences.findByIdForVisibility(evidenceId).orElseThrow(ResourceNotFoundException::new);
        UUID caseId = visibleEvidence.getCustodyCase().getId();
        access.requireReadableCase(caseId, actor);

        // Detach the plain (unlocked) visibility read before acquiring the lock: if a concurrent append
        // commits between the two reads, the persistence context would otherwise hold two conflicting
        // versions of the same DigitalEvidence row and Hibernate would raise a StaleObjectStateException
        // when materializing the locked read, turning a benign race into a 500.
        entityManager.detach(visibleEvidence);

        DigitalEvidence lockedEvidence =
                evidences.findByIdForChainVerification(evidenceId).orElseThrow(ResourceNotFoundException::new);
        long storedEventCount = lockedEvidence.getCustodyEventCount();
        String storedHeadHash = lockedEvidence.getCustodyChainHeadHash();

        List<CustodyEventSnapshot> snapshots = events.findAllByEvidenceIdOrderBySequenceNumberAsc(evidenceId).stream()
                .map(CustodyChainVerificationService::toSnapshot)
                .toList();

        CustodyChainVerificationResult result =
                verifier.verify(evidenceId, caseId, storedEventCount, storedHeadHash, snapshots);
        log(caseId, result);

        return new CustodyChainVerificationResponse(
                result.evidenceId(),
                result.valid(),
                result.checkedEvents(),
                result.storedEventCount(),
                result.loadedEventCount(),
                result.storedHeadHash(),
                result.calculatedHeadHash(),
                result.brokenAtEventId(),
                result.brokenAtSequenceNumber(),
                result.reason(),
                result.expectedValue(),
                result.actualValue(),
                Instant.now(clock).truncatedTo(ChronoUnit.MICROS));
    }

    private void log(UUID caseId, CustodyChainVerificationResult result) {
        if (result.valid()) {
            LOGGER.info(
                    "Custody chain verification result=valid caseId={} evidenceId={} valid={} checkedEvents={} "
                            + "failureReason={} brokenSequence={}",
                    caseId,
                    result.evidenceId(),
                    true,
                    result.checkedEvents(),
                    null,
                    null);
        } else {
            LOGGER.warn(
                    "Custody chain verification result=invalid caseId={} evidenceId={} valid={} checkedEvents={} "
                            + "failureReason={} brokenSequence={}",
                    caseId,
                    result.evidenceId(),
                    false,
                    result.checkedEvents(),
                    result.reason(),
                    result.brokenAtSequenceNumber());
        }
    }

    private static CustodyEventSnapshot toSnapshot(CustodyEvent event) {
        return new CustodyEventSnapshot(
                event.getId(),
                event.getCustodyCase().getId(),
                event.getEvidence().getId(),
                event.getOperator().getId(),
                event.getActorRole(),
                event.getSequenceNumber(),
                event.getEventType(),
                event.getOccurredAt(),
                event.getHashVersion(),
                event.getPayloadVersion(),
                event.getPayloadJson(),
                event.getPreviousHash(),
                event.getEventHash());
    }
}
