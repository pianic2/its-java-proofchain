package it.itsprodigi.proofchain.custodycase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.custodycase.api.CaseResponse;
import it.itsprodigi.proofchain.custodycase.api.CreateCaseRequest;
import it.itsprodigi.proofchain.custodycase.api.PatchCaseMetadataRequest;
import it.itsprodigi.proofchain.custodycase.api.UpdateCaseStatusRequest;
import it.itsprodigi.proofchain.custodycase.application.CaseAccessService;
import it.itsprodigi.proofchain.custodycase.application.ConcurrentCaseModificationException;
import it.itsprodigi.proofchain.custodycase.application.CustodyCaseService;
import it.itsprodigi.proofchain.custodycase.domain.CaseMembership;
import it.itsprodigi.proofchain.custodycase.domain.CasePriority;
import it.itsprodigi.proofchain.custodycase.domain.CaseStatus;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.custodycase.persistence.CustodyCaseRepository;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

class CustodyCaseApplicationIT extends PostgreSqlIntegrationTest {

    @Autowired
    private CustodyCaseService service;

    @MockitoSpyBean
    private CaseAccessService access;

    @MockitoSpyBean
    private CustodyCaseRepository custodyCases;

    @MockitoSpyBean
    private CaseMembershipRepository memberships;

    @Autowired
    private OperatorRepository operators;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private ExecutorService executor;
    private Operator admin;
    private Operator manager;
    private Operator auditor;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        admin = operators.saveAndFlush(operator("admin", OperatorRole.ADMIN));
        manager = operators.saveAndFlush(operator("manager", OperatorRole.CASE_MANAGER));
        auditor = operators.saveAndFlush(operator("auditor", OperatorRole.AUDITOR));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        try {
            if (executor != null) {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            SecurityContextHolder.clearContext();
            cleanDatabase();
            executor = null;
        }
    }

    @Test
    void creationCommitsCaseAndCreatorMembershipForBothAuthorizedRoles() {
        CaseResponse adminCase = createAs(admin, "Admin case");
        CaseResponse managerCase = createAs(manager, "Manager case");

        assertThat(custodyCases.count()).isEqualTo(2);
        assertThat(memberships.count()).isEqualTo(2);
        assertThat(memberships.existsByCustodyCaseIdAndOperatorId(adminCase.id(), admin.getId()))
                .isTrue();
        assertThat(memberships.existsByCustodyCaseIdAndOperatorId(managerCase.id(), manager.getId()))
                .isTrue();
    }

    @Test
    void aMembershipFailureRollsBackTheAlreadyFlushedCaseInsert() {
        authenticate(manager);
        doAnswer(invocation -> {
                    custodyCases.flush();
                    throw new DataIntegrityViolationException("forced membership failure");
                })
                .when(memberships)
                .saveAndFlush(any(CaseMembership.class));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.create(request("Rolled back case"), actor(manager)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(custodyCases.count()).isZero();
        assertThat(memberships.count()).isZero();
    }

    @Test
    void accessibleListingAndDetailDoNotLeakCasesAcrossMemberships() {
        CustodyCase managerCase = persistedCase("Manager case", manager);
        CustodyCase auditorCase = persistedCase("Auditor case", auditor);
        persistedCase("Admin case", admin);

        authenticate(manager);
        var managerPage = service.list(0, 20, List.of(), actor(manager));
        authenticate(auditor);
        var auditorPage = service.list(0, 20, List.of(), actor(auditor));
        authenticate(admin);
        var adminPage = service.list(0, 20, List.of(), actor(admin));

        assertThat(managerPage.content()).extracting(CaseResponse::id).containsExactly(managerCase.getId());
        assertThat(auditorPage.content()).extracting(CaseResponse::id).containsExactly(auditorCase.getId());
        assertThat(adminPage.content()).hasSize(3);
        authenticate(manager);
        assertThat(service.get(managerCase.getId(), actor(manager)).id()).isEqualTo(managerCase.getId());
    }

    @Test
    void concurrentMetadataCommandsProduceOneCommitAndOneTranslatedConflict() throws Exception {
        CustodyCase custodyCase = persistedCase("Concurrent case", manager);
        PatchCaseMetadataRequest firstPatch = patch("First concurrent title");
        PatchCaseMetadataRequest secondPatch = patch("Second concurrent title");
        CyclicBarrier bothCasesLoaded = new CyclicBarrier(2);
        doAnswer(invocation -> {
                    CustodyCase loaded = (CustodyCase) invocation.callRealMethod();
                    bothCasesLoaded.await(10, TimeUnit.SECONDS);
                    return loaded;
                })
                .when(access)
                .requireMetadataModificationPermission(custodyCase.getId(), actor(manager));
        executor = Executors.newFixedThreadPool(2);

        Future<Object> first = executor.submit(() -> updateResult(custodyCase.getId(), firstPatch));
        Future<Object> second = executor.submit(() -> updateResult(custodyCase.getId(), secondPatch));
        Object firstResult = first.get(20, TimeUnit.SECONDS);
        Object secondResult = second.get(20, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(List.of(firstResult, secondResult))
                .filteredOn(CaseResponse.class::isInstance)
                .hasSize(1);
        assertThat(List.of(firstResult, secondResult))
                .filteredOn(ConcurrentCaseModificationException.class::isInstance)
                .hasSize(1);
        CustodyCase persisted = custodyCases.findById(custodyCase.getId()).orElseThrow();
        assertThat(persisted.getTitle()).isIn("First concurrent title", "Second concurrent title");
        assertThat(persisted.getVersion()).isEqualTo(1L);
    }

    @Test
    void concurrentClosureCommandsProduceOneTransitionAndOneTranslatedConflict() throws Exception {
        CustodyCase custodyCase = persistedCase("Concurrent closure case", manager);
        var closeRequest = new UpdateCaseStatusRequest(CaseStatus.CLOSED);
        CyclicBarrier bothCasesLoaded = new CyclicBarrier(2);
        doAnswer(invocation -> {
                    CustodyCase loaded = (CustodyCase) invocation.callRealMethod();
                    bothCasesLoaded.await(10, TimeUnit.SECONDS);
                    return loaded;
                })
                .when(access)
                .requireClosurePermission(custodyCase.getId(), actor(manager));
        executor = Executors.newFixedThreadPool(2);

        Future<Object> first = executor.submit(() -> statusResult(custodyCase.getId(), closeRequest));
        Future<Object> second = executor.submit(() -> statusResult(custodyCase.getId(), closeRequest));
        Object firstResult = first.get(20, TimeUnit.SECONDS);
        Object secondResult = second.get(20, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        List<Object> results = List.of(firstResult, secondResult);
        assertThat(results).filteredOn(CaseResponse.class::isInstance).hasSize(1);
        assertThat(results)
                .filteredOn(ConcurrentCaseModificationException.class::isInstance)
                .hasSize(1);
        CaseResponse committed = results.stream()
                .filter(CaseResponse.class::isInstance)
                .map(CaseResponse.class::cast)
                .findFirst()
                .orElseThrow();
        CustodyCase persisted = custodyCases.findById(custodyCase.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(CaseStatus.CLOSED);
        assertThat(persisted.getVersion()).isEqualTo(1L);
        assertThat(persisted.getClosedAt()).isNotNull().isEqualTo(persisted.getUpdatedAt());
        assertThat(committed.closedAt()).isEqualTo(persisted.getClosedAt());
        assertThat(committed.updatedAt()).isEqualTo(persisted.getUpdatedAt());
    }

    private CaseResponse createAs(Operator operator, String title) {
        authenticate(operator);
        return service.create(request(title), actor(operator));
    }

    private Object updateResult(UUID caseId, PatchCaseMetadataRequest patch) {
        try {
            authenticate(manager);
            return service.updateMetadata(caseId, patch, actor(manager));
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    private Object statusResult(UUID caseId, UpdateCaseStatusRequest request) {
        try {
            authenticate(manager);
            return service.updateStatus(caseId, request, actor(manager));
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    private void cleanDatabase() {
        memberships.deleteAll();
        custodyCases.deleteAll();
        operators.deleteAll();
    }

    private CustodyCase persistedCase(String title, Operator member) {
        CustodyCase custodyCase = custodyCases.saveAndFlush(
                CustodyCase.create(title, null, null, null, null, CasePriority.MEDIUM, admin));
        memberships.saveAndFlush(CaseMembership.assign(custodyCase, member, admin));
        return custodyCase;
    }

    private static CreateCaseRequest request(String title) {
        return new CreateCaseRequest(title, null, null, null, null, CasePriority.HIGH);
    }

    private static PatchCaseMetadataRequest patch(String title) throws Exception {
        return JsonMapper.builder()
                .build()
                .readValue("{\"title\":\"%s\"}".formatted(title), PatchCaseMetadataRequest.class);
    }

    private void authenticate(Operator operator) {
        AuthenticatedOperator actor = actor(operator);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        actor,
                        null,
                        List.of(new SimpleGrantedAuthority(
                                "ROLE_" + operator.getRole().name()))));
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
                operator.getCreatedAt(),
                operator.getUpdatedAt());
    }

    private Operator operator(String username, OperatorRole role) {
        return Operator.create(
                username, username + "@example.com", passwordEncoder.encode("correct-password"), "First", "Last", role);
    }
}
