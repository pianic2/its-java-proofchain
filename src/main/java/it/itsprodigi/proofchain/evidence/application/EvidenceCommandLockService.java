package it.itsprodigi.proofchain.evidence.application;

import it.itsprodigi.proofchain.common.exception.ResourceNotFoundException;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodycase.persistence.CustodyCaseRepository;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single owner of the frozen operational lock order.
 *
 * <pre>
 * PESSIMISTIC_READ CustodyCase -&gt; PESSIMISTIC_WRITE DigitalEvidence
 * </pre>
 *
 * <p>{@link #lockEvidence(CaseReadLock, UUID)} requires the {@link CaseReadLock} token produced by {@link
 * #lockCase(UUID)}, so no evidence lock can be acquired before the case read lock. The case lock is never upgraded, at
 * most one evidence is locked per command, and target operators and memberships are never pessimistically locked.
 */
@Service
public class EvidenceCommandLockService {

    private final CustodyCaseRepository custodyCases;
    private final DigitalEvidenceRepository evidences;

    public EvidenceCommandLockService(CustodyCaseRepository custodyCases, DigitalEvidenceRepository evidences) {
        this.custodyCases = Objects.requireNonNull(custodyCases, "custodyCases must not be null");
        this.evidences = Objects.requireNonNull(evidences, "evidences must not be null");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CaseReadLock lockCase(UUID caseId) {
        Objects.requireNonNull(caseId, "caseId must not be null");
        CustodyCase custodyCase = custodyCases.findByIdForShare(caseId).orElseThrow(ResourceNotFoundException::new);
        return new CaseReadLock(custodyCase);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public DigitalEvidence lockEvidence(CaseReadLock caseLock, UUID evidenceId) {
        Objects.requireNonNull(caseLock, "caseLock must not be null");
        Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        return evidences
                .findByIdAndCaseIdForCommand(evidenceId, caseLock.caseId())
                .orElseThrow(ResourceNotFoundException::new);
    }
}
