package it.itsprodigi.proofchain.custodycase.application;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.common.exception.ResourceNotFoundException;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.custodycase.persistence.CustodyCaseRepository;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CaseAccessService {

    private final CustodyCaseRepository custodyCases;
    private final CaseMembershipRepository memberships;

    public CaseAccessService(CustodyCaseRepository custodyCases, CaseMembershipRepository memberships) {
        this.custodyCases = Objects.requireNonNull(custodyCases, "custodyCases must not be null");
        this.memberships = Objects.requireNonNull(memberships, "memberships must not be null");
    }

    @Transactional(readOnly = true)
    public CustodyCase requireReadableCase(UUID caseId, AuthenticatedOperator actor) {
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        if (actor.role() != OperatorRole.ADMIN && !memberships.existsByCustodyCaseIdAndOperatorId(caseId, actor.id())) {
            throw new ResourceNotFoundException();
        }
        return custodyCases.findByIdWithCreatedBy(caseId).orElseThrow(ResourceNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public Page<CustodyCase> findAccessibleCases(Pageable pageable, AuthenticatedOperator actor) {
        Objects.requireNonNull(pageable, "pageable must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        return actor.role() == OperatorRole.ADMIN
                ? custodyCases.findPageForAdmin(pageable)
                : custodyCases.findPageForMember(actor.id(), pageable);
    }

    @Transactional(readOnly = true)
    public CustodyCase requireMetadataModificationPermission(UUID caseId, AuthenticatedOperator actor) {
        return requireCaseManagerPermission(caseId, actor);
    }

    @Transactional(readOnly = true)
    public CustodyCase requireMembershipManagementPermission(UUID caseId, AuthenticatedOperator actor) {
        return requireCaseManagerPermission(caseId, actor);
    }

    @Transactional(readOnly = true)
    public CustodyCase requireClosurePermission(UUID caseId, AuthenticatedOperator actor) {
        return requireCaseManagerPermission(caseId, actor);
    }

    @Transactional(readOnly = true)
    public CustodyCase requireEvidenceRegistrationPermission(UUID caseId, AuthenticatedOperator actor) {
        CustodyCase custodyCase = requireReadableCase(caseId, actor);
        if (actor.role() != OperatorRole.ADMIN
                && actor.role() != OperatorRole.CASE_MANAGER
                && actor.role() != OperatorRole.EVIDENCE_OFFICER) {
            throw new AccessDeniedException("The visible custody case cannot receive evidence from this operator.");
        }
        return custodyCase;
    }

    private CustodyCase requireCaseManagerPermission(UUID caseId, AuthenticatedOperator actor) {
        CustodyCase custodyCase = requireReadableCase(caseId, actor);
        if (actor.role() != OperatorRole.ADMIN && actor.role() != OperatorRole.CASE_MANAGER) {
            throw new AccessDeniedException("The visible custody case cannot be modified by this operator.");
        }
        return custodyCase;
    }
}
