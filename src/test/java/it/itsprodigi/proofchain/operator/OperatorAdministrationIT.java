package it.itsprodigi.proofchain.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.itsprodigi.proofchain.operator.api.CreateOperatorRequest;
import it.itsprodigi.proofchain.operator.api.OperatorPageResponse;
import it.itsprodigi.proofchain.operator.api.UpdateOperatorRoleRequest;
import it.itsprodigi.proofchain.operator.api.UpdateOperatorStatusRequest;
import it.itsprodigi.proofchain.operator.application.ConcurrentOperatorModificationException;
import it.itsprodigi.proofchain.operator.application.DuplicateOperatorException;
import it.itsprodigi.proofchain.operator.application.OperatorAdminService;
import it.itsprodigi.proofchain.operator.application.OperatorRequestValidationException;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

class OperatorAdministrationIT extends PostgreSqlIntegrationTest {

    private static final String PASSWORD = "secure-password";

    @Autowired
    private OperatorAdminService service;

    @Autowired
    private OperatorRepository operators;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void cleanOperators() {
        operators.deleteAll();
        authenticateAsAdmin();
    }

    @Test
    void createPersistsActiveOperatorWithOnlyBcryptPasswordHash() {
        var response = service.create(new CreateOperatorRequest(
                " New.Operator ", " New.Operator@Example.COM ", PASSWORD, " New ", " Operator ", OperatorRole.ADMIN));

        Operator saved = operators.findById(response.id()).orElseThrow();
        assertThat(saved.getUsername()).isEqualTo("new.operator");
        assertThat(saved.getEmail()).isEqualTo("new.operator@example.com");
        assertThat(saved.getStatus()).isEqualTo(OperatorStatus.ACTIVE);
        assertThat(saved.getPasswordHash()).startsWith("$2").hasSize(60);
        assertThat(passwordEncoder.matches(PASSWORD, saved.getPasswordHash())).isTrue();
    }

    @Test
    void duplicateIdentityPrecheckReturnsControllableConflict() {
        service.create(new CreateOperatorRequest(
                "duplicate", "duplicate@example.com", PASSWORD, "First", "Last", OperatorRole.AUDITOR));

        assertThatThrownBy(() -> service.create(new CreateOperatorRequest(
                        " DUPLICATE ", "other@example.com", PASSWORD, "First", "Last", OperatorRole.AUDITOR)))
                .isInstanceOf(DuplicateOperatorException.class);
        assertThatThrownBy(() -> service.create(new CreateOperatorRequest(
                        "other", " DUPLICATE@EXAMPLE.COM ", PASSWORD, "First", "Last", OperatorRole.AUDITOR)))
                .isInstanceOf(DuplicateOperatorException.class);
    }

    @Test
    void uniqueConstraintRacesReturnControllableDuplicateConflicts() throws Exception {
        assertUniqueConstraintRace(
                "race-username", "race-username@example.com", "race-username", "other-username@example.com");
        assertUniqueConstraintRace("race-email", "race-email@example.com", "other-email", "race-email@example.com");
    }

    @Test
    void paginationIsExplicitDeterministicAndSupportsAllowedSorts() {
        for (String username : List.of("charlie", "alpha", "bravo")) {
            service.create(new CreateOperatorRequest(
                    username, username + "@example.com", PASSWORD, "First", "Last", OperatorRole.AUDITOR));
        }

        OperatorPageResponse page = service.list(0, 2, List.of("username,asc"));

        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.content()).extracting(response -> response.username()).containsExactly("alpha", "bravo");
        assertThat(page.sort().field()).isEqualTo("username");
        assertThat(page.sort().direction()).isEqualTo("asc");
        assertThatThrownBy(() -> service.list(0, 20, List.of("unknown,asc")))
                .isInstanceOf(OperatorRequestValidationException.class);
    }

    @Test
    void suspendedAndDisabledOperatorsCanBeReactivated() {
        Operator suspended = operators.saveAndFlush(operator("suspended", OperatorStatus.SUSPENDED));
        Operator disabled = operators.saveAndFlush(operator("disabled", OperatorStatus.DISABLED));

        assertThat(service.updateStatus(
                                suspended.getId(),
                                new UpdateOperatorStatusRequest(OperatorStatus.ACTIVE),
                                UUID.randomUUID())
                        .status())
                .isEqualTo(OperatorStatus.ACTIVE);
        assertThat(service.updateStatus(
                                disabled.getId(),
                                new UpdateOperatorStatusRequest(OperatorStatus.ACTIVE),
                                UUID.randomUUID())
                        .status())
                .isEqualTo(OperatorStatus.ACTIVE);
    }

    @Test
    void concurrentUpdatesToSameOperatorProduceOneTranslatedOptimisticLockConflict() {
        Operator target = operators.saveAndFlush(operator("optimistic", OperatorStatus.ACTIVE));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
                        operators.findById(target.getId()).orElseThrow();
                        Future<UpdateResult> winner =
                                executor.submit(() -> updateRoleAsAdmin(target.getId(), OperatorRole.CASE_MANAGER));
                        assertThat(await(winner).success()).isTrue();
                        service.updateRole(
                                target.getId(),
                                new UpdateOperatorRoleRequest(OperatorRole.EVIDENCE_OFFICER),
                                UUID.randomUUID());
                    }))
                    .isInstanceOf(ConcurrentOperatorModificationException.class);
        } finally {
            executor.shutdownNow();
        }

        Operator reloaded = operators.findById(target.getId()).orElseThrow();
        assertThat(reloaded.getRole()).isEqualTo(OperatorRole.CASE_MANAGER);
        assertThat(reloaded.getVersion()).isEqualTo(1L);
    }

    private void authenticateAsAdmin() {
        SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        "test-admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    private UpdateResult updateRoleAsAdmin(UUID targetId, OperatorRole role) {
        authenticateAsAdmin();
        try {
            service.updateRole(targetId, new UpdateOperatorRoleRequest(role), UUID.randomUUID());
            return new UpdateResult(true, null);
        } catch (RuntimeException exception) {
            return new UpdateResult(false, exception);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Operator operator(String username, OperatorStatus status) {
        Operator operator = Operator.create(
                username,
                username + "@example.com",
                passwordEncoder.encode(PASSWORD),
                "First",
                "Last",
                OperatorRole.AUDITOR);
        operator.changeStatus(status);
        return operator;
    }

    private void assertUniqueConstraintRace(
            String blockingUsername, String blockingEmail, String candidateUsername, String candidateEmail)
            throws Exception {
        UUID blockingId = UUID.randomUUID();
        try (Connection blockingConnection = dataSource.getConnection()) {
            blockingConnection.setAutoCommit(false);
            insertBlockingOperator(blockingConnection, blockingId, blockingUsername, blockingEmail);

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<RuntimeException> attempt = executor.submit(() -> {
                    authenticateAsAdmin();
                    try {
                        service.create(new CreateOperatorRequest(
                                candidateUsername, candidateEmail, PASSWORD, "First", "Last", OperatorRole.AUDITOR));
                        return null;
                    } catch (RuntimeException exception) {
                        return exception;
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                });

                assertThatThrownBy(() -> attempt.get(5, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
                blockingConnection.commit();
                assertThat(attempt.get(5, TimeUnit.SECONDS)).isInstanceOf(DuplicateOperatorException.class);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static UpdateResult await(Future<UpdateResult> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("Concurrent test coordination failed", exception);
        }
    }

    private static void insertBlockingOperator(Connection connection, UUID id, String username, String email)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO operators (
                    id, username, email, password_hash, first_name, last_name,
                    role, status, created_at, updated_at, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            Instant now = Instant.now();
            statement.setObject(1, id);
            statement.setString(2, username);
            statement.setString(3, email);
            statement.setString(4, "$2a$10$01234567890123456789012345678901234567890123456789012");
            statement.setString(5, "First");
            statement.setString(6, "Last");
            statement.setString(7, OperatorRole.AUDITOR.name());
            statement.setString(8, OperatorStatus.ACTIVE.name());
            statement.setTimestamp(9, Timestamp.from(now));
            statement.setTimestamp(10, Timestamp.from(now));
            statement.setLong(11, 0L);
            statement.executeUpdate();
        }
    }

    private record UpdateResult(boolean success, RuntimeException failure) {}
}
