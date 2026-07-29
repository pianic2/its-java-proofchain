package it.itsprodigi.proofchain.custodycase.application;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.custodycase.api.MembershipResponse;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class CaseMembershipService {

    private final CaseMembershipTransactions transactions;

    public CaseMembershipService(CaseMembershipTransactions transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
    }

    @PreAuthorize("isAuthenticated()")
    public List<MembershipResponse> list(UUID caseId, AuthenticatedOperator actor) {
        return transactions.list(caseId, actor);
    }

    @PreAuthorize("isAuthenticated()")
    public MembershipAssignmentResult assign(UUID caseId, UUID operatorId, AuthenticatedOperator actor) {
        try {
            return transactions.assign(caseId, operatorId, actor);
        } catch (DuplicateMembershipRaceException exception) {
            MembershipResponse existing = transactions
                    .findExistingAfterDuplicate(caseId, operatorId)
                    .orElseThrow(ConcurrentMembershipConflictException::new);
            return new MembershipAssignmentResult(existing, false);
        } catch (PessimisticLockingFailureException exception) {
            throw new ConcurrentMembershipConflictException();
        }
    }

    @PreAuthorize("isAuthenticated()")
    public void remove(UUID caseId, UUID operatorId, AuthenticatedOperator actor) {
        try {
            transactions.remove(caseId, operatorId, actor);
        } catch (PessimisticLockingFailureException exception) {
            throw new ConcurrentMembershipConflictException();
        }
    }
}
