package it.itsprodigi.proofchain.evidence.application;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.common.exception.ResourceNotFoundException;
import it.itsprodigi.proofchain.custodycase.application.CaseAccessService;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceRepository;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import jakarta.persistence.EntityManager;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Centralizes visibility and command authorization for every Sprint 5 operational custody command.
 *
 * <p>Nonexistent evidence and evidence hidden from a non-ADMIN non-member produce the same {@link
 * ResourceNotFoundException}. A visible caller lacking the command permission produces {@link AccessDeniedException}.
 */
@Service
public class EvidenceOperationalAccessService {

    private final DigitalEvidenceRepository evidences;
    private final CaseAccessService caseAccess;
    private final CaseMembershipRepository memberships;
    private final OperatorRepository operators;
    private final EntityManager entityManager;

    public EvidenceOperationalAccessService(
            DigitalEvidenceRepository evidences,
            CaseAccessService caseAccess,
            CaseMembershipRepository memberships,
            OperatorRepository operators,
            EntityManager entityManager) {
        this.evidences = Objects.requireNonNull(evidences, "evidences must not be null");
        this.caseAccess = Objects.requireNonNull(caseAccess, "caseAccess must not be null");
        this.memberships = Objects.requireNonNull(memberships, "memberships must not be null");
        this.operators = Objects.requireNonNull(operators, "operators must not be null");
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    }

    /**
     * Visibility-aware lookup resolving only the immutable case identifier of the target evidence. Never discloses
     * whether hidden evidence exists.
     */
    @Transactional(readOnly = true)
    public UUID requireVisibleCaseId(UUID evidenceId, AuthenticatedOperator actor) {
        Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        UUID caseId = evidences.findCaseIdById(evidenceId).orElseThrow(ResourceNotFoundException::new);
        caseAccess.requireVisibleCase(caseId, actor);
        return caseId;
    }

    /**
     * Re-evaluates visibility, membership, status and role from currently committed operator state. Must be invoked
     * after the custody case read lock has been acquired.
     */
    @Transactional(readOnly = true)
    public Operator requireAuthorizedActor(
            EvidenceOperationalCommand command, UUID caseId, AuthenticatedOperator actor) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Operator currentActor = operators.findById(actor.id()).orElseThrow(ResourceNotFoundException::new);
        entityManager.refresh(currentActor);
        boolean admin = currentActor.getRole() == OperatorRole.ADMIN;
        if (!admin && !memberships.existsByCustodyCaseIdAndOperatorId(caseId, currentActor.getId())) {
            throw new ResourceNotFoundException();
        }
        if (currentActor.getStatus() != OperatorStatus.ACTIVE) {
            throw new AccessDeniedException("The current operator cannot run operational custody commands.");
        }
        if (!command.allowsRole(currentActor.getRole())) {
            throw new AccessDeniedException("The visible evidence cannot receive this command from this operator.");
        }
        return currentActor;
    }

    /**
     * Applies the current-holder restriction. Must be invoked after the evidence write lock has been acquired, because
     * the holder is only trustworthy once the aggregate is locked.
     */
    @Transactional(readOnly = true)
    public void requireAuthorizedHolder(EvidenceOperationalCommand command, Operator actor, DigitalEvidence evidence) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        if (!command.evidenceOfficerMustBeCurrentHolder() || actor.getRole() != OperatorRole.EVIDENCE_OFFICER) {
            return;
        }
        Operator holder = evidence.getCurrentHolder();
        if (holder == null || !holder.getId().equals(actor.getId())) {
            throw new AccessDeniedException("The visible evidence cannot receive this command from this operator.");
        }
    }
}
