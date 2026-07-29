package it.itsprodigi.proofchain.custodycase.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.custodycase.api.CaseOperatorSummaryResponse;
import it.itsprodigi.proofchain.custodycase.api.MembershipResponse;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CaseMembershipServiceTest {

    @Mock
    private CaseMembershipTransactions transactions;

    private CaseMembershipService service;

    @BeforeEach
    void setUp() {
        service = new CaseMembershipService(transactions);
    }

    @Test
    void recoversDuplicateAssignmentOnlyAfterTheFailedTransactionHasReturned() {
        UUID caseId = UUID.randomUUID();
        UUID operatorId = UUID.randomUUID();
        AuthenticatedOperator actor = actor();
        MembershipResponse existing = response(caseId, operatorId);
        when(transactions.assign(caseId, operatorId, actor))
                .thenThrow(new DuplicateMembershipRaceException(new IllegalStateException("unique violation")));
        when(transactions.findExistingAfterDuplicate(caseId, operatorId)).thenReturn(Optional.of(existing));

        MembershipAssignmentResult result = service.assign(caseId, operatorId, actor);

        assertThat(result.created()).isFalse();
        assertThat(result.membership()).isSameAs(existing);
    }

    @Test
    void mapsAnUnrecoverableDuplicateRaceToTheDedicatedConflict() {
        UUID caseId = UUID.randomUUID();
        UUID operatorId = UUID.randomUUID();
        AuthenticatedOperator actor = actor();
        when(transactions.assign(caseId, operatorId, actor))
                .thenThrow(new DuplicateMembershipRaceException(new IllegalStateException("unique violation")));
        when(transactions.findExistingAfterDuplicate(caseId, operatorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assign(caseId, operatorId, actor))
                .isInstanceOf(ConcurrentMembershipConflictException.class);
    }

    private static AuthenticatedOperator actor() {
        return new AuthenticatedOperator(
                UUID.randomUUID(),
                "manager",
                "manager@example.com",
                "Case",
                "Manager",
                OperatorRole.CASE_MANAGER,
                OperatorStatus.ACTIVE,
                Instant.EPOCH,
                Instant.EPOCH);
    }

    private static MembershipResponse response(UUID caseId, UUID operatorId) {
        var operator = new CaseOperatorSummaryResponse(
                operatorId, "member", "Case", "Member", OperatorRole.AUDITOR, OperatorStatus.ACTIVE);
        return new MembershipResponse(UUID.randomUUID(), caseId, operator, operator, Instant.EPOCH);
    }
}
