package it.itsprodigi.proofchain.custodycase.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.custodycase.domain.CaseMembership;
import it.itsprodigi.proofchain.custodycase.domain.CasePriority;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.custodycase.persistence.CustodyCaseRepository;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import jakarta.persistence.EntityManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class CaseMembershipTransactionsTest {

    private static final String BCRYPT_HASH = "$2a$10$01234567890123456789012345678901234567890123456789012";

    @Mock
    private CustodyCaseRepository custodyCases;

    @Mock
    private CaseMembershipRepository memberships;

    @Mock
    private OperatorRepository operators;

    @Mock
    private CaseAccessService access;

    @Mock
    private EntityManager entityManager;

    private CaseMembershipTransactions transactions;
    private Operator manager;
    private Operator member;
    private CustodyCase custodyCase;
    private AuthenticatedOperator actor;

    @BeforeEach
    void setUp() {
        transactions = new CaseMembershipTransactions(
                custodyCases, memberships, operators, access, new CaseMembershipMapper(), entityManager);
        manager = operator("manager", OperatorRole.CASE_MANAGER);
        member = operator("member", OperatorRole.AUDITOR);
        custodyCase = CustodyCase.create("Membership case", null, null, null, null, CasePriority.HIGH, manager);
        actor = actor(manager);
    }

    @Test
    void existingMembershipIsIdempotentWithoutRewritingMetadataOrRevalidatingTargetState() {
        CaseMembership existing = CaseMembership.assign(custodyCase, member, manager);
        member.changeStatus(OperatorStatus.SUSPENDED);
        permitAndLock();
        when(memberships.findByCaseIdAndOperatorId(custodyCase.getId(), member.getId()))
                .thenReturn(Optional.of(existing));

        MembershipAssignmentResult result = transactions.assign(custodyCase.getId(), member.getId(), actor);

        assertThat(result.created()).isFalse();
        assertThat(result.membership().id()).isEqualTo(existing.getId());
        assertThat(result.membership().assignedAt()).isEqualTo(existing.getAssignedAt());
        verify(operators, never()).findByIdForUpdate(member.getId());
        verify(access).requireMembershipManagementPermission(custodyCase.getId(), actor);
    }

    @Test
    void newMembershipRequiresActiveNonAdminTargetAndRecordsTheActor() {
        permitAndLock();
        when(memberships.findByCaseIdAndOperatorId(custodyCase.getId(), member.getId()))
                .thenReturn(Optional.empty());
        when(operators.findById(member.getId())).thenReturn(Optional.of(member));
        when(operators.findByIdForUpdate(member.getId())).thenReturn(Optional.of(member));
        when(operators.findById(manager.getId())).thenReturn(Optional.of(manager));
        when(memberships.saveAndFlush(any(CaseMembership.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MembershipAssignmentResult result = transactions.assign(custodyCase.getId(), member.getId(), actor);

        assertThat(result.created()).isTrue();
        assertThat(result.membership().operator().id()).isEqualTo(member.getId());
        assertThat(result.membership().assignedBy().id()).isEqualTo(manager.getId());

        Operator suspended = operator("suspended", OperatorRole.AUDITOR);
        suspended.changeStatus(OperatorStatus.SUSPENDED);
        when(memberships.findByCaseIdAndOperatorId(custodyCase.getId(), suspended.getId()))
                .thenReturn(Optional.empty());
        when(operators.findById(suspended.getId())).thenReturn(Optional.of(suspended));
        when(operators.findByIdForUpdate(suspended.getId())).thenReturn(Optional.of(suspended));
        assertThatThrownBy(() -> transactions.assign(custodyCase.getId(), suspended.getId(), actor))
                .isInstanceOf(OperatorNotActiveException.class);

        Operator admin = operator("admin", OperatorRole.ADMIN);
        when(memberships.findByCaseIdAndOperatorId(custodyCase.getId(), admin.getId()))
                .thenReturn(Optional.empty());
        when(operators.findById(admin.getId())).thenReturn(Optional.of(admin));
        assertThatThrownBy(() -> transactions.assign(custodyCase.getId(), admin.getId(), actor))
                .isInstanceOf(AdminMembershipNotAssignableException.class);
    }

    @Test
    void closedStatePrecedesAssignmentAndRemovalIdempotency() {
        custodyCase.close();
        permitAndLock();

        assertThatThrownBy(() -> transactions.assign(custodyCase.getId(), member.getId(), actor))
                .isInstanceOf(CaseClosedException.class);
        assertThatThrownBy(() -> transactions.remove(custodyCase.getId(), UUID.randomUUID(), actor))
                .isInstanceOf(CaseClosedException.class);
        verify(memberships, never()).findByCaseIdAndOperatorId(any(), any());
    }

    @Test
    void removalIsIdempotentButRejectsTheLastCurrentResponsibleManager() {
        permitAndLock();
        UUID absentId = UUID.randomUUID();
        when(memberships.findByCaseIdAndOperatorId(custodyCase.getId(), absentId))
                .thenReturn(Optional.empty());
        transactions.remove(custodyCase.getId(), absentId, actor);
        verify(memberships, never()).delete(any());

        CaseMembership managerMembership = CaseMembership.assign(custodyCase, manager, manager);
        when(memberships.findByCaseIdAndOperatorId(custodyCase.getId(), manager.getId()))
                .thenReturn(Optional.of(managerMembership));
        when(memberships.countResponsibleManagers(custodyCase.getId())).thenReturn(1L);
        assertThatThrownBy(() -> transactions.remove(custodyCase.getId(), manager.getId(), actor))
                .isInstanceOf(LastCaseManagerRemovalException.class);

        when(memberships.countResponsibleManagers(custodyCase.getId())).thenReturn(2L);
        transactions.remove(custodyCase.getId(), manager.getId(), actor);
        verify(memberships).delete(managerMembership);
        verify(memberships).flush();
    }

    @Test
    void adminCreatorRemovalUsesTheSameResponsibleManagerInvariantWithoutChangingCreator() {
        Operator admin = operator("admin", OperatorRole.ADMIN);
        CustodyCase adminCase = CustodyCase.create("Admin case", null, null, null, null, CasePriority.HIGH, admin);
        AuthenticatedOperator adminActor = actor(admin);
        CaseMembership adminMembership = CaseMembership.assign(adminCase, admin, admin);
        when(access.requireMembershipManagementPermission(adminCase.getId(), adminActor))
                .thenReturn(adminCase);
        when(custodyCases.findByIdForUpdate(adminCase.getId())).thenReturn(Optional.of(adminCase));
        when(operators.findById(admin.getId())).thenReturn(Optional.of(admin));
        when(memberships.findByCaseIdAndOperatorId(adminCase.getId(), admin.getId()))
                .thenReturn(Optional.of(adminMembership));
        when(memberships.countResponsibleManagers(adminCase.getId())).thenReturn(1L, 2L);

        assertThatThrownBy(() -> transactions.remove(adminCase.getId(), admin.getId(), adminActor))
                .isInstanceOf(LastCaseManagerRemovalException.class);
        transactions.remove(adminCase.getId(), admin.getId(), adminActor);

        verify(memberships).delete(adminMembership);
        assertThat(adminCase.getCreatedBy()).isSameAs(admin);
    }

    @Test
    void actorRoleAndStatusAreRevalidatedAfterTheCaseLock() {
        permitAndLock();
        manager.changeRole(OperatorRole.AUDITOR);

        assertThatThrownBy(() -> transactions.remove(custodyCase.getId(), member.getId(), actor))
                .isInstanceOf(AccessDeniedException.class);
        verify(memberships, never()).findByCaseIdAndOperatorId(custodyCase.getId(), member.getId());
    }

    @Test
    void namedDuplicateViolationTriggersPostRollbackRecoveryAndOtherIntegrityFailuresAreSanitized() {
        permitAndLock();
        when(memberships.findByCaseIdAndOperatorId(custodyCase.getId(), member.getId()))
                .thenReturn(Optional.empty());
        when(operators.findById(member.getId())).thenReturn(Optional.of(member));
        when(operators.findByIdForUpdate(member.getId())).thenReturn(Optional.of(member));
        when(operators.findById(manager.getId())).thenReturn(Optional.of(manager));
        var namedViolation = new org.hibernate.exception.ConstraintViolationException(
                "duplicate", new SQLException("duplicate"), "insert", "uk_case_memberships_case_operator");
        when(memberships.saveAndFlush(any(CaseMembership.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate", namedViolation));

        assertThatThrownBy(() -> transactions.assign(custodyCase.getId(), member.getId(), actor))
                .isInstanceOf(DuplicateMembershipRaceException.class);

        Operator other = operator("other", OperatorRole.AUDITOR);
        when(memberships.findByCaseIdAndOperatorId(custodyCase.getId(), other.getId()))
                .thenReturn(Optional.empty());
        when(operators.findById(other.getId())).thenReturn(Optional.of(other));
        when(operators.findByIdForUpdate(other.getId())).thenReturn(Optional.of(other));
        when(memberships.saveAndFlush(any(CaseMembership.class)))
                .thenThrow(new DataIntegrityViolationException("unclassified"));

        assertThatThrownBy(() -> transactions.assign(custodyCase.getId(), other.getId(), actor))
                .isInstanceOf(ConcurrentMembershipConflictException.class);
    }

    private void permitAndLock() {
        when(access.requireMembershipManagementPermission(custodyCase.getId(), actor))
                .thenReturn(custodyCase);
        when(custodyCases.findByIdForUpdate(custodyCase.getId())).thenReturn(Optional.of(custodyCase));
        when(operators.findById(manager.getId())).thenReturn(Optional.of(manager));
        when(memberships.existsByCustodyCaseIdAndOperatorId(custodyCase.getId(), manager.getId()))
                .thenReturn(true);
    }

    private static Operator operator(String username, OperatorRole role) {
        return Operator.create(username, username + "@example.com", BCRYPT_HASH, "Case", "Operator", role);
    }

    private static AuthenticatedOperator actor(Operator operator) {
        return new AuthenticatedOperator(
                operator.getId(),
                operator.getUsername(),
                operator.getEmail(),
                operator.getFirstName(),
                operator.getLastName(),
                operator.getRole(),
                operator.getStatus(),
                Instant.EPOCH,
                Instant.EPOCH);
    }
}
