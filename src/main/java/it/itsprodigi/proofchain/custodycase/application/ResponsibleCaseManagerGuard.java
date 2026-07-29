package it.itsprodigi.proofchain.custodycase.application;

import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.custodycase.persistence.CustodyCaseRepository;
import it.itsprodigi.proofchain.operator.application.OperatorInvariantException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ResponsibleCaseManagerGuard {

    private final CustodyCaseRepository custodyCases;
    private final CaseMembershipRepository memberships;

    public ResponsibleCaseManagerGuard(CustodyCaseRepository custodyCases, CaseMembershipRepository memberships) {
        this.custodyCases = Objects.requireNonNull(custodyCases, "custodyCases must not be null");
        this.memberships = Objects.requireNonNull(memberships, "memberships must not be null");
    }

    public List<CustodyCase> lockAffectedCases(UUID operatorId) {
        Objects.requireNonNull(operatorId, "operatorId must not be null");
        List<UUID> caseIds = memberships.findCaseIdsByOperatorIdOrderByCaseId(operatorId);
        if (caseIds.isEmpty()) {
            return List.of();
        }
        return custodyCases.lockAllByIdInOrderById(caseIds);
    }

    public void requireStableAffectedCases(List<CustodyCase> lockedCases, UUID operatorId) {
        Objects.requireNonNull(lockedCases, "lockedCases must not be null");
        Objects.requireNonNull(operatorId, "operatorId must not be null");
        List<UUID> lockedCaseIds = lockedCases.stream().map(CustodyCase::getId).toList();
        List<UUID> currentCaseIds = memberships.findCaseIdsByOperatorIdOrderByCaseId(operatorId);
        if (!currentCaseIds.equals(lockedCaseIds)) {
            throw new AffectedCaseSetChangedException();
        }
    }

    public void requireAnotherResponsibleManager(List<CustodyCase> affectedCases, UUID excludedOperatorId) {
        Objects.requireNonNull(affectedCases, "affectedCases must not be null");
        Objects.requireNonNull(excludedOperatorId, "excludedOperatorId must not be null");
        if (affectedCases.isEmpty()) {
            return;
        }

        List<UUID> caseIds = affectedCases.stream().map(CustodyCase::getId).toList();
        Set<UUID> casesWithAnotherResponsibleManager = new HashSet<>();
        memberships.countOtherResponsibleManagersByCase(caseIds, excludedOperatorId).stream()
                .filter(count -> count.getResponsibleCount() > 0)
                .map(CaseMembershipRepository.ResponsibleManagerCount::getCaseId)
                .forEach(casesWithAnotherResponsibleManager::add);

        if (!casesWithAnotherResponsibleManager.containsAll(caseIds)) {
            throw new OperatorInvariantException(
                    "The operation would leave one or more custody cases without a responsible manager.");
        }
    }
}
