package it.itsprodigi.proofchain.custodycase.application;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.common.exception.ResourceNotFoundException;
import it.itsprodigi.proofchain.custodycase.api.MembershipResponse;
import it.itsprodigi.proofchain.custodycase.domain.CaseMembership;
import it.itsprodigi.proofchain.custodycase.domain.CaseStatus;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.custodycase.persistence.CustodyCaseRepository;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CaseMembershipTransactions {

    private static final Set<String> DUPLICATE_CONSTRAINTS = Set.of("uk_case_memberships_case_operator");

    private final CustodyCaseRepository custodyCases;
    private final CaseMembershipRepository memberships;
    private final OperatorRepository operators;
    private final CaseAccessService access;
    private final CaseMembershipMapper mapper;
    private final EntityManager entityManager;

    public CaseMembershipTransactions(
            CustodyCaseRepository custodyCases,
            CaseMembershipRepository memberships,
            OperatorRepository operators,
            CaseAccessService access,
            CaseMembershipMapper mapper,
            EntityManager entityManager) {
        this.custodyCases = Objects.requireNonNull(custodyCases, "custodyCases must not be null");
        this.memberships = Objects.requireNonNull(memberships, "memberships must not be null");
        this.operators = Objects.requireNonNull(operators, "operators must not be null");
        this.access = Objects.requireNonNull(access, "access must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    }

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public List<MembershipResponse> list(UUID caseId, AuthenticatedOperator actor) {
        access.requireReadableCase(caseId, actor);
        return memberships.findAllByCustodyCaseIdOrderByAssignedAtAscIdAsc(caseId).stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Transactional
    @PreAuthorize("isAuthenticated()")
    public MembershipAssignmentResult assign(UUID caseId, UUID operatorId, AuthenticatedOperator actor) {
        requireMembershipMutationPermission(caseId, actor);
        CustodyCase custodyCase = lockCase(caseId);
        Operator currentActor = requireCurrentMembershipMutationPermission(caseId, actor);
        requireOpen(custodyCase);

        Optional<CaseMembership> existing = memberships.findByCaseIdAndOperatorId(caseId, operatorId);
        if (existing.isPresent()) {
            return new MembershipAssignmentResult(mapper.toResponse(existing.orElseThrow()), false);
        }

        Operator observedTarget = operators.findById(operatorId).orElseThrow(ResourceNotFoundException::new);
        if (observedTarget.getRole() == OperatorRole.ADMIN) {
            throw new AdminMembershipNotAssignableException();
        }
        Operator target = operators.findByIdForUpdate(operatorId).orElseThrow(ResourceNotFoundException::new);
        entityManager.refresh(target);
        if (target.getStatus() != OperatorStatus.ACTIVE) {
            throw new OperatorNotActiveException();
        }
        if (target.getRole() == OperatorRole.ADMIN) {
            throw new AdminMembershipNotAssignableException();
        }
        CaseMembership membership = CaseMembership.assign(custodyCase, target, currentActor);
        try {
            return new MembershipAssignmentResult(mapper.toResponse(memberships.saveAndFlush(membership)), true);
        } catch (DataIntegrityViolationException exception) {
            if (hasNamedConstraint(exception, DUPLICATE_CONSTRAINTS)) {
                throw new DuplicateMembershipRaceException(exception);
            }
            throw new ConcurrentMembershipConflictException();
        }
    }

    @Transactional
    @PreAuthorize("isAuthenticated()")
    public void remove(UUID caseId, UUID operatorId, AuthenticatedOperator actor) {
        requireMembershipMutationPermission(caseId, actor);
        CustodyCase custodyCase = lockCase(caseId);
        requireCurrentMembershipMutationPermission(caseId, actor);
        requireOpen(custodyCase);

        Optional<CaseMembership> existing = memberships.findByCaseIdAndOperatorId(caseId, operatorId);
        if (existing.isEmpty()) {
            return;
        }
        CaseMembership membership = existing.orElseThrow();
        if (isResponsibleManager(membership.getOperator()) && memberships.countResponsibleManagers(caseId) <= 1) {
            throw new LastCaseManagerRemovalException();
        }
        memberships.delete(membership);
        memberships.flush();
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    @PreAuthorize("isAuthenticated()")
    public Optional<MembershipResponse> findExistingAfterDuplicate(UUID caseId, UUID operatorId) {
        return memberships.findByCaseIdAndOperatorId(caseId, operatorId).map(mapper::toResponse);
    }

    private void requireMembershipMutationPermission(UUID caseId, AuthenticatedOperator actor) {
        access.requireMembershipManagementPermission(caseId, actor);
    }

    private Operator requireCurrentMembershipMutationPermission(UUID caseId, AuthenticatedOperator actor) {
        Operator currentActor = operators.findById(actor.id()).orElseThrow(ResourceNotFoundException::new);
        entityManager.refresh(currentActor);
        boolean admin = currentActor.getRole() == OperatorRole.ADMIN;
        if (!admin && !memberships.existsByCustodyCaseIdAndOperatorId(caseId, currentActor.getId())) {
            throw new ResourceNotFoundException();
        }
        if (currentActor.getStatus() != OperatorStatus.ACTIVE
                || !admin && currentActor.getRole() != OperatorRole.CASE_MANAGER) {
            throw new AccessDeniedException("The visible custody case cannot be modified by this operator.");
        }
        return currentActor;
    }

    private CustodyCase lockCase(UUID caseId) {
        CustodyCase custodyCase = custodyCases.findByIdForUpdate(caseId).orElseThrow(ResourceNotFoundException::new);
        entityManager.refresh(custodyCase);
        return custodyCase;
    }

    private static void requireOpen(CustodyCase custodyCase) {
        if (custodyCase.getStatus() == CaseStatus.CLOSED) {
            throw new CaseClosedException();
        }
    }

    private static boolean isResponsibleManager(Operator operator) {
        return operator.getStatus() == OperatorStatus.ACTIVE
                && (operator.getRole() == OperatorRole.ADMIN || operator.getRole() == OperatorRole.CASE_MANAGER);
    }

    private static boolean hasNamedConstraint(Throwable exception, Set<String> names) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ConstraintViolationException violation
                    && names.contains(violation.getConstraintName())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
