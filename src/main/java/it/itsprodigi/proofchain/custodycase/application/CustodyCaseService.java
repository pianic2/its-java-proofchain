package it.itsprodigi.proofchain.custodycase.application;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.common.exception.ResourceNotFoundException;
import it.itsprodigi.proofchain.custodycase.api.CasePageResponse;
import it.itsprodigi.proofchain.custodycase.api.CaseResponse;
import it.itsprodigi.proofchain.custodycase.api.CreateCaseRequest;
import it.itsprodigi.proofchain.custodycase.api.PatchCaseMetadataRequest;
import it.itsprodigi.proofchain.custodycase.api.UpdateCaseStatusRequest;
import it.itsprodigi.proofchain.custodycase.domain.CaseMembership;
import it.itsprodigi.proofchain.custodycase.domain.CaseStatus;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.custodycase.persistence.CustodyCaseRepository;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import jakarta.persistence.OptimisticLockException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustodyCaseService {

    private final CustodyCaseRepository custodyCases;
    private final CaseMembershipRepository memberships;
    private final OperatorRepository operators;
    private final CaseAccessService access;
    private final CaseMapper mapper;

    public CustodyCaseService(
            CustodyCaseRepository custodyCases,
            CaseMembershipRepository memberships,
            OperatorRepository operators,
            CaseAccessService access,
            CaseMapper mapper) {
        this.custodyCases = Objects.requireNonNull(custodyCases, "custodyCases must not be null");
        this.memberships = Objects.requireNonNull(memberships, "memberships must not be null");
        this.operators = Objects.requireNonNull(operators, "operators must not be null");
        this.access = Objects.requireNonNull(access, "access must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'CASE_MANAGER')")
    @Transactional
    public CaseResponse create(CreateCaseRequest request, AuthenticatedOperator actor) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Operator creator = operators.findById(actor.id()).orElseThrow(ResourceNotFoundException::new);
        CustodyCase custodyCase;
        try {
            custodyCase = CustodyCase.create(
                    request.title(),
                    request.description(),
                    request.authorityName(),
                    request.externalReference(),
                    request.location(),
                    request.priority(),
                    creator);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new CaseRequestValidationException("custody case request is invalid", exception);
        }
        custodyCases.save(custodyCase);
        memberships.saveAndFlush(CaseMembership.assign(custodyCase, creator, creator));
        return mapper.toResponse(custodyCase);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public CasePageResponse list(int page, int size, List<String> sortParameters, AuthenticatedOperator actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        validatePage(page, size, sortParameters);
        PageRequest pageable = PageRequest.of(page, size);
        Page<CustodyCase> result = access.findAccessibleCases(pageable, actor);
        return mapper.toPage(result);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public CaseResponse get(UUID caseId, AuthenticatedOperator actor) {
        return mapper.toResponse(access.requireReadableCase(caseId, actor));
    }

    @Transactional
    @PreAuthorize("isAuthenticated()")
    public CaseResponse updateMetadata(UUID caseId, PatchCaseMetadataRequest request, AuthenticatedOperator actor) {
        Objects.requireNonNull(request, "request must not be null");
        CustodyCase custodyCase = access.requireMetadataModificationPermission(caseId, actor);
        if (custodyCase.getStatus() == CaseStatus.CLOSED) {
            throw new CaseClosedException();
        }
        if (!request.hasAnyField()
                || request.hasTitle() && request.getTitle() == null
                || request.hasPriority() && request.getPriority() == null) {
            throw new CaseRequestValidationException("custody case patch is invalid");
        }
        try {
            custodyCase.updateMetadata(
                    request.hasTitle() ? request.getTitle() : custodyCase.getTitle(),
                    request.hasDescription() ? request.getDescription() : custodyCase.getDescription(),
                    request.hasAuthorityName() ? request.getAuthorityName() : custodyCase.getAuthorityName(),
                    request.hasExternalReference()
                            ? request.getExternalReference()
                            : custodyCase.getExternalReference(),
                    request.hasLocation() ? request.getLocation() : custodyCase.getLocation(),
                    request.hasPriority() ? request.getPriority() : custodyCase.getPriority());
        } catch (IllegalArgumentException exception) {
            throw new CaseRequestValidationException("custody case patch is invalid", exception);
        }
        flushAfterUpdate();
        return mapper.toResponse(custodyCase);
    }

    @Transactional
    @PreAuthorize("isAuthenticated()")
    public CaseResponse updateStatus(UUID caseId, UpdateCaseStatusRequest request, AuthenticatedOperator actor) {
        Objects.requireNonNull(request, "request must not be null");
        CustodyCase custodyCase = access.requireClosurePermission(caseId, actor);
        if (request.status() != CaseStatus.CLOSED) {
            throw new InvalidCaseStatusTransitionException();
        }
        if (custodyCase.getStatus() == CaseStatus.CLOSED) {
            return mapper.toResponse(custodyCase);
        }
        custodyCase.close();
        flushAfterUpdate();
        return mapper.toResponse(custodyCase);
    }

    private void flushAfterUpdate() {
        try {
            custodyCases.flush();
        } catch (OptimisticLockingFailureException | OptimisticLockException exception) {
            throw new ConcurrentCaseModificationException(exception);
        }
    }

    private static void validatePage(int page, int size, List<String> sortParameters) {
        if (page < 0) {
            throw new CaseRequestValidationException("page must be greater than or equal to zero");
        }
        if (size < 1 || size > 100) {
            throw new CaseRequestValidationException("size must be between 1 and 100");
        }
        if (sortParameters != null && !sortParameters.isEmpty()) {
            throw new CaseRequestValidationException("client-controlled sorting is not supported");
        }
    }
}
