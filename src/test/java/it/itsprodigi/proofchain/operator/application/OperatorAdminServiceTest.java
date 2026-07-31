package it.itsprodigi.proofchain.operator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import it.itsprodigi.proofchain.common.config.PasswordSecurityProperties;
import it.itsprodigi.proofchain.custodycase.application.AffectedCaseSetChangedException;
import it.itsprodigi.proofchain.custodycase.application.ResponsibleCaseManagerGuard;
import it.itsprodigi.proofchain.operator.api.CreateOperatorRequest;
import it.itsprodigi.proofchain.operator.api.OperatorDetailResponse;
import it.itsprodigi.proofchain.operator.api.UpdateOperatorRoleRequest;
import it.itsprodigi.proofchain.operator.api.UpdateOperatorStatusRequest;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class OperatorAdminServiceTest {

    private static final String PASSWORD = "p".repeat(12);
    private static final String BCRYPT_HASH = "$2a$10$01234567890123456789012345678901234567890123456789012";
    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    @Mock
    private OperatorRepository operators;

    @Mock
    private ResponsibleCaseManagerGuard responsibleCaseManagers;

    @Mock
    private OperatorMutationTransaction mutationTransaction;

    @Mock
    private EntityManager entityManager;

    private PasswordEncoder passwordEncoder;
    private OperatorAdminService service;

    @AfterAll
    static void closeValidator() {
        VALIDATOR_FACTORY.close();
    }

    @BeforeEach
    void setUp() {
        PasswordSecurityProperties properties = new PasswordSecurityProperties();
        properties.setBcryptStrength(4);
        passwordEncoder = new BCryptPasswordEncoder(4);
        service = new OperatorAdminService(
                operators,
                new OperatorMapper(),
                new PasswordPolicy(properties),
                passwordEncoder,
                VALIDATOR,
                responsibleCaseManagers,
                mutationTransaction,
                entityManager);
        lenient().when(mutationTransaction.execute(any())).thenAnswer(invocation -> {
            Supplier<?> mutation = invocation.getArgument(0);
            return mutation.get();
        });
        lenient().when(operators.saveAndFlush(any(Operator.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(operators.findById(any(UUID.class))).thenReturn(Optional.empty());
        lenient()
                .when(operators.findByIdForUpdate(any(UUID.class)))
                .thenAnswer(invocation -> operators.findById(invocation.getArgument(0)));
        lenient().when(operators.existsByUsername(any(String.class))).thenReturn(false);
        lenient().when(operators.existsByEmail(any(String.class))).thenReturn(false);
    }

    @Test
    void createsEveryFrozenRoleAsAnActiveOperatorWithEncodedPassword() {
        for (OperatorRole role : OperatorRole.values()) {
            CreateOperatorRequest request = new CreateOperatorRequest(
                    " User-" + role.name(), " " + role.name() + "@Example.COM ", PASSWORD, " Ada ", " Admin ", role);

            OperatorDetailResponse response = service.create(request);

            assertThat(response.role()).isEqualTo(role);
            assertThat(response.status()).isEqualTo(OperatorStatus.ACTIVE);
            ArgumentCaptor<Operator> saved = ArgumentCaptor.forClass(Operator.class);
            verify(operators).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getUsername())
                    .isEqualTo("user-" + role.name().toLowerCase(Locale.ROOT));
            assertThat(saved.getValue().getEmail()).isEqualTo(role.name().toLowerCase(Locale.ROOT) + "@example.com");
            assertThat(saved.getValue().getPasswordHash()).startsWith("$2").hasSize(60);
            assertThat(passwordEncoder.matches(PASSWORD, saved.getValue().getPasswordHash()))
                    .isTrue();
            clearInvocations(operators);
        }
    }

    @Test
    void rejectsDuplicateUsernameAndEmailBeforeEncoding() {
        doReturn(true).when(operators).existsByUsername("existing");
        CreateOperatorRequest usernameRequest = request("existing", "new@example.com", OperatorRole.AUDITOR);

        assertThatThrownBy(() -> service.create(usernameRequest)).isInstanceOf(DuplicateOperatorException.class);

        doReturn(true).when(operators).existsByEmail("existing@example.com");
        assertThatThrownBy(() -> service.create(request("new", "existing@example.com", OperatorRole.AUDITOR)))
                .isInstanceOf(DuplicateOperatorException.class);
        verify(operators, never()).saveAndFlush(any());
    }

    @Test
    void protectsTheLastActiveAdminFromRoleAndStatusChanges() {
        Operator admin = operator("admin", OperatorRole.ADMIN, OperatorStatus.ACTIVE);
        when(operators.findById(admin.getId())).thenReturn(Optional.of(admin));
        when(operators.lockActiveAdmins()).thenReturn(List.of(admin));

        assertThatThrownBy(() -> service.updateRole(
                        admin.getId(), new UpdateOperatorRoleRequest(OperatorRole.AUDITOR), UUID.randomUUID()))
                .isInstanceOf(OperatorInvariantException.class)
                .hasMessage("The operation would leave ProofChain without an ACTIVE ADMIN.");
        assertThatThrownBy(() -> service.updateStatus(
                        admin.getId(), new UpdateOperatorStatusRequest(OperatorStatus.SUSPENDED), UUID.randomUUID()))
                .isInstanceOf(OperatorInvariantException.class)
                .hasMessage("The operation would leave ProofChain without an ACTIVE ADMIN.");
        assertThatThrownBy(() -> service.updateStatus(
                        admin.getId(), new UpdateOperatorStatusRequest(OperatorStatus.DISABLED), UUID.randomUUID()))
                .isInstanceOf(OperatorInvariantException.class)
                .hasMessage("The operation would leave ProofChain without an ACTIVE ADMIN.");
    }

    @Test
    void forbidsSelfSuspensionAndSelfDisablement() {
        Operator admin = operator("admin", OperatorRole.ADMIN, OperatorStatus.ACTIVE);
        when(operators.findById(admin.getId())).thenReturn(Optional.of(admin));
        when(operators.lockActiveAdmins())
                .thenReturn(List.of(admin, operator("other", OperatorRole.ADMIN, OperatorStatus.ACTIVE)));

        assertThatThrownBy(() -> service.updateStatus(
                        admin.getId(), new UpdateOperatorStatusRequest(OperatorStatus.SUSPENDED), admin.getId()))
                .isInstanceOf(OperatorInvariantException.class)
                .hasMessage("An ADMIN cannot suspend or disable itself.");
        assertThatThrownBy(() -> service.updateStatus(
                        admin.getId(), new UpdateOperatorStatusRequest(OperatorStatus.DISABLED), admin.getId()))
                .isInstanceOf(OperatorInvariantException.class)
                .hasMessage("An ADMIN cannot suspend or disable itself.");
    }

    @Test
    void allowsSelfDemotionOnlyWhenAnotherActiveAdminExists() {
        Operator admin = operator("admin", OperatorRole.ADMIN, OperatorStatus.ACTIVE);
        Operator other = operator("other", OperatorRole.ADMIN, OperatorStatus.ACTIVE);
        when(operators.findById(admin.getId())).thenReturn(Optional.of(admin));
        when(operators.lockActiveAdmins()).thenReturn(List.of(admin, other));

        OperatorDetailResponse response =
                service.updateRole(admin.getId(), new UpdateOperatorRoleRequest(OperatorRole.AUDITOR), admin.getId());

        assertThat(response.role()).isEqualTo(OperatorRole.AUDITOR);
        assertThat(admin.getRole()).isEqualTo(OperatorRole.AUDITOR);
        verify(operators).flush();

        Operator onlyAdmin = operator("only-admin", OperatorRole.ADMIN, OperatorStatus.ACTIVE);
        when(operators.findById(onlyAdmin.getId())).thenReturn(Optional.of(onlyAdmin));
        when(operators.lockActiveAdmins()).thenReturn(List.of(onlyAdmin));
        assertThatThrownBy(() -> service.updateRole(
                        onlyAdmin.getId(), new UpdateOperatorRoleRequest(OperatorRole.AUDITOR), onlyAdmin.getId()))
                .isInstanceOf(OperatorInvariantException.class)
                .hasMessage("Self-demotion requires another ACTIVE ADMIN.");
    }

    @Test
    void sameRoleAndStatusAreIdempotentAndSuspendedOrDisabledCanReactivate() {
        Operator admin = operator("admin", OperatorRole.ADMIN, OperatorStatus.ACTIVE);
        when(operators.findById(admin.getId())).thenReturn(Optional.of(admin));
        assertThat(service.updateRole(admin.getId(), new UpdateOperatorRoleRequest(OperatorRole.ADMIN), admin.getId())
                        .role())
                .isEqualTo(OperatorRole.ADMIN);
        assertThat(service.updateStatus(
                                admin.getId(), new UpdateOperatorStatusRequest(OperatorStatus.ACTIVE), admin.getId())
                        .status())
                .isEqualTo(OperatorStatus.ACTIVE);
        verify(operators, never()).lockActiveAdmins();
        verify(operators, never()).flush();

        for (OperatorStatus inactive : new OperatorStatus[] {OperatorStatus.SUSPENDED, OperatorStatus.DISABLED}) {
            Operator operator = operator(inactive.name().toLowerCase(Locale.ROOT), OperatorRole.AUDITOR, inactive);
            when(operators.findById(operator.getId())).thenReturn(Optional.of(operator));
            assertThat(service.updateStatus(
                                    operator.getId(),
                                    new UpdateOperatorStatusRequest(OperatorStatus.ACTIVE),
                                    admin.getId())
                            .status())
                    .isEqualTo(OperatorStatus.ACTIVE);
        }
    }

    @Test
    void locksGlobalAdminsThenAffectedCasesThenOperatorBeforeReducingCaseResponsibility() {
        Operator manager = operator("manager", OperatorRole.CASE_MANAGER, OperatorStatus.ACTIVE);
        when(operators.findById(manager.getId())).thenReturn(Optional.of(manager));
        when(responsibleCaseManagers.lockAffectedCases(manager.getId())).thenReturn(List.of());

        OperatorDetailResponse response = service.updateRole(
                manager.getId(), new UpdateOperatorRoleRequest(OperatorRole.AUDITOR), UUID.randomUUID());

        assertThat(response.role()).isEqualTo(OperatorRole.AUDITOR);
        var ordered = inOrder(operators, responsibleCaseManagers);
        ordered.verify(operators).lockActiveAdmins();
        ordered.verify(responsibleCaseManagers).lockAffectedCases(manager.getId());
        ordered.verify(operators).findByIdForUpdate(manager.getId());
        ordered.verify(responsibleCaseManagers).requireStableAffectedCases(List.of(), manager.getId());
        ordered.verify(responsibleCaseManagers).requireAnotherResponsibleManager(List.of(), manager.getId());
    }

    @Test
    void retriesInANewTransactionWhenTheAffectedMembershipSetChangedAfterOperatorLock() {
        Operator manager = operator("retry-manager", OperatorRole.CASE_MANAGER, OperatorStatus.ACTIVE);
        when(operators.findById(manager.getId())).thenReturn(Optional.of(manager));
        when(responsibleCaseManagers.lockAffectedCases(manager.getId())).thenReturn(List.of());
        doThrow(new AffectedCaseSetChangedException())
                .doNothing()
                .when(responsibleCaseManagers)
                .requireStableAffectedCases(List.of(), manager.getId());

        OperatorDetailResponse response = service.updateRole(
                manager.getId(), new UpdateOperatorRoleRequest(OperatorRole.AUDITOR), UUID.randomUUID());

        assertThat(response.role()).isEqualTo(OperatorRole.AUDITOR);
        verify(mutationTransaction, times(2)).execute(any());
        verify(responsibleCaseManagers, times(2)).lockAffectedCases(manager.getId());
        verify(operators, times(2)).findByIdForUpdate(manager.getId());
        verify(operators).flush();
    }

    @Test
    void mapsRoleRetryExhaustionToConcurrentModificationAfterExactlyThreeRolledBackAttempts() {
        Operator manager = operator("exhausted-role", OperatorRole.CASE_MANAGER, OperatorStatus.ACTIVE);
        when(operators.findById(manager.getId())).thenReturn(Optional.of(manager));
        when(responsibleCaseManagers.lockAffectedCases(manager.getId())).thenReturn(List.of());
        doThrow(new AffectedCaseSetChangedException())
                .when(responsibleCaseManagers)
                .requireStableAffectedCases(List.of(), manager.getId());

        assertThatThrownBy(() -> service.updateRole(
                        manager.getId(), new UpdateOperatorRoleRequest(OperatorRole.AUDITOR), UUID.randomUUID()))
                .isInstanceOf(ConcurrentOperatorModificationException.class)
                .hasCauseInstanceOf(AffectedCaseSetChangedException.class);

        verify(mutationTransaction, times(3)).execute(any());
        verify(responsibleCaseManagers, times(3)).requireStableAffectedCases(List.of(), manager.getId());
        verify(operators, times(3)).findByIdForUpdate(manager.getId());
        verify(operators, never()).flush();
        assertThat(manager.getRole()).isEqualTo(OperatorRole.CASE_MANAGER);
    }

    @Test
    void mapsStatusRetryExhaustionWithoutPartiallyChangingTheOperator() {
        Operator manager = operator("exhausted-status", OperatorRole.CASE_MANAGER, OperatorStatus.ACTIVE);
        when(operators.findById(manager.getId())).thenReturn(Optional.of(manager));
        when(responsibleCaseManagers.lockAffectedCases(manager.getId())).thenReturn(List.of());
        doThrow(new AffectedCaseSetChangedException())
                .when(responsibleCaseManagers)
                .requireStableAffectedCases(List.of(), manager.getId());

        assertThatThrownBy(() -> service.updateStatus(
                        manager.getId(), new UpdateOperatorStatusRequest(OperatorStatus.SUSPENDED), UUID.randomUUID()))
                .isInstanceOf(ConcurrentOperatorModificationException.class)
                .hasCauseInstanceOf(AffectedCaseSetChangedException.class);

        verify(mutationTransaction, times(3)).execute(any());
        verify(responsibleCaseManagers, times(3)).requireStableAffectedCases(List.of(), manager.getId());
        verify(operators, never()).flush();
        assertThat(manager.getStatus()).isEqualTo(OperatorStatus.ACTIVE);
    }

    @Test
    void rejectsRoleMutationWhenTheLockedVersionDiffersFromTheObservedVersion() {
        UUID operatorId = UUID.randomUUID();
        Operator observed = mock(Operator.class);
        Operator locked = mock(Operator.class);
        when(observed.getRole()).thenReturn(OperatorRole.CASE_MANAGER);
        when(observed.getStatus()).thenReturn(OperatorStatus.ACTIVE);
        when(observed.getVersion()).thenReturn(4L);
        when(locked.getVersion()).thenReturn(5L);
        when(operators.findById(operatorId)).thenReturn(Optional.of(observed), Optional.of(locked));
        doReturn(Optional.of(locked)).when(operators).findByIdForUpdate(operatorId);
        when(responsibleCaseManagers.lockAffectedCases(operatorId)).thenReturn(List.of());

        assertThatThrownBy(() -> service.updateRole(
                        operatorId, new UpdateOperatorRoleRequest(OperatorRole.AUDITOR), UUID.randomUUID()))
                .isInstanceOf(ConcurrentOperatorModificationException.class)
                .hasCauseInstanceOf(jakarta.persistence.OptimisticLockException.class);

        verify(locked, never()).changeRole(any(OperatorRole.class));
        verify(operators, never()).flush();
        verify(responsibleCaseManagers, never()).requireStableAffectedCases(List.of(), operatorId);
    }

    @Test
    void rejectsStatusMutationWhenTheLockedVersionDiffersFromTheObservedVersion() {
        UUID operatorId = UUID.randomUUID();
        Operator observed = mock(Operator.class);
        Operator locked = mock(Operator.class);
        when(observed.getRole()).thenReturn(OperatorRole.CASE_MANAGER);
        when(observed.getStatus()).thenReturn(OperatorStatus.ACTIVE);
        when(observed.getVersion()).thenReturn(8L);
        when(locked.getVersion()).thenReturn(9L);
        when(operators.findById(operatorId)).thenReturn(Optional.of(observed), Optional.of(locked));
        doReturn(Optional.of(locked)).when(operators).findByIdForUpdate(operatorId);
        when(responsibleCaseManagers.lockAffectedCases(operatorId)).thenReturn(List.of());

        assertThatThrownBy(() -> service.updateStatus(
                        operatorId, new UpdateOperatorStatusRequest(OperatorStatus.SUSPENDED), UUID.randomUUID()))
                .isInstanceOf(ConcurrentOperatorModificationException.class)
                .hasCauseInstanceOf(jakarta.persistence.OptimisticLockException.class);

        verify(locked, never()).changeStatus(any(OperatorStatus.class));
        verify(operators, never()).flush();
        verify(responsibleCaseManagers, never()).requireStableAffectedCases(List.of(), operatorId);
    }

    @Test
    void validatesAllowlistedSortAndAddsUuidTieBreakerBeforeRepositoryExecution() {
        when(operators.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        assertThat(service.list(0, 20, List.of()).sort())
                .isEqualTo(new it.itsprodigi.proofchain.operator.api.OperatorSortResponse("username", "asc"));
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(operators).findAll(pageable.capture());
        assertThat(pageable.getValue()
                        .getSort()
                        .getOrderFor("username")
                        .getDirection()
                        .name())
                .isEqualTo("ASC");
        assertThat(pageable.getValue().getSort().getOrderFor("id")).isNotNull();

        for (String invalid : new String[] {"unknown,asc", "username,up", "username", "username,asc,role", "id,asc"}) {
            assertThatThrownBy(() -> service.list(0, 20, List.of(invalid)))
                    .isInstanceOf(OperatorRequestValidationException.class);
        }
        assertThatThrownBy(() -> service.list(0, 20, List.of("username,asc", "email,asc")))
                .isInstanceOf(OperatorRequestValidationException.class);
        assertThatThrownBy(() -> service.list(-1, 20, List.of()))
                .isInstanceOf(OperatorRequestValidationException.class);
        assertThatThrownBy(() -> service.list(0, 101, List.of()))
                .isInstanceOf(OperatorRequestValidationException.class);
    }

    @Test
    void mapperDetailDoesNotExposeHashOrVersion() {
        Operator operator = operator("admin", OperatorRole.ADMIN, OperatorStatus.ACTIVE);

        OperatorDetailResponse detail = new OperatorMapper().toDetail(operator);

        assertThat(detail)
                .extracting(
                        "id", "username", "email", "firstName", "lastName", "role", "status", "createdAt", "updatedAt")
                .doesNotContain(BCRYPT_HASH, operator.getVersion());
    }

    private static CreateOperatorRequest request(String username, String email, OperatorRole role) {
        return new CreateOperatorRequest(username, email, PASSWORD, "Jane", "Doe", role);
    }

    private static Operator operator(String username, OperatorRole role, OperatorStatus status) {
        Operator operator = Operator.create(username, username + "@example.com", BCRYPT_HASH, "Jane", "Doe", role);
        if (status != OperatorStatus.ACTIVE) {
            operator.changeStatus(status);
        }
        return operator;
    }
}
