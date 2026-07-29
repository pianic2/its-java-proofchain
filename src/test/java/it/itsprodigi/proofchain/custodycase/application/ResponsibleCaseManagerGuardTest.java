package it.itsprodigi.proofchain.custodycase.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.custodycase.persistence.CustodyCaseRepository;
import it.itsprodigi.proofchain.operator.application.OperatorInvariantException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResponsibleCaseManagerGuardTest {

    @Mock
    private CustodyCaseRepository custodyCases;

    @Mock
    private CaseMembershipRepository memberships;

    private ResponsibleCaseManagerGuard guard;

    @BeforeEach
    void setUp() {
        guard = new ResponsibleCaseManagerGuard(custodyCases, memberships);
    }

    @Test
    void locksEveryAffectedCaseInTheRepositoryDefinedUuidOrder() {
        UUID operatorId = UUID.randomUUID();
        List<UUID> orderedIds = List.of(
                UUID.fromString("00000000-0000-4000-8000-000000000001"),
                UUID.fromString("00000000-0000-4000-8000-000000000002"));
        List<CustodyCase> locked = List.of(mock(CustodyCase.class), mock(CustodyCase.class));
        when(memberships.findCaseIdsByOperatorIdOrderByCaseId(operatorId)).thenReturn(orderedIds);
        when(custodyCases.lockAllByIdInOrderById(orderedIds)).thenReturn(locked);

        assertThat(guard.lockAffectedCases(operatorId)).isSameAs(locked);
        verify(custodyCases).lockAllByIdInOrderById(orderedIds);
    }

    @Test
    void rejectsAnOperatorReductionWhenAnyLockedCaseHasNoAlternativeResponsibleManager() {
        UUID excludedOperatorId = UUID.randomUUID();
        CustodyCase first = mock(CustodyCase.class);
        CustodyCase second = mock(CustodyCase.class);
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        when(first.getId()).thenReturn(firstId);
        when(second.getId()).thenReturn(secondId);
        CaseMembershipRepository.ResponsibleManagerCount firstCount =
                mock(CaseMembershipRepository.ResponsibleManagerCount.class);
        when(firstCount.getCaseId()).thenReturn(firstId);
        when(firstCount.getResponsibleCount()).thenReturn(1L);
        when(memberships.countOtherResponsibleManagersByCase(List.of(firstId, secondId), excludedOperatorId))
                .thenReturn(List.of(firstCount));

        assertThatThrownBy(() -> guard.requireAnotherResponsibleManager(List.of(first, second), excludedOperatorId))
                .isInstanceOf(OperatorInvariantException.class)
                .hasMessage("The operation would leave one or more custody cases without a responsible manager.");
    }

    @Test
    void forcesAFullTransactionRetryWhenMembershipsChangedAfterCaseLocksWereSelected() {
        UUID operatorId = UUID.randomUUID();
        CustodyCase locked = mock(CustodyCase.class);
        UUID lockedId = UUID.randomUUID();
        UUID phantomId = UUID.randomUUID();
        when(locked.getId()).thenReturn(lockedId);
        when(memberships.findCaseIdsByOperatorIdOrderByCaseId(operatorId)).thenReturn(List.of(lockedId, phantomId));

        assertThatThrownBy(() -> guard.requireStableAffectedCases(List.of(locked), operatorId))
                .isInstanceOf(AffectedCaseSetChangedException.class);
    }
}
