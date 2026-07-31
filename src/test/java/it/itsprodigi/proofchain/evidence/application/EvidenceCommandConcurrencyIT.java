package it.itsprodigi.proofchain.evidence.application;

import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.custodyCase;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.evidence;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.operator;
import static org.assertj.core.api.Assertions.assertThat;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.custodycase.domain.CaseMembership;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.custodycase.persistence.CustodyCaseRepository;
import it.itsprodigi.proofchain.custodyevent.domain.CustodyEvent;
import it.itsprodigi.proofchain.custodyevent.persistence.CustodyEventRepository;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventHashing;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyTransferredPayload;
import it.itsprodigi.proofchain.evidence.api.EvidenceOperationResponse;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceRepository;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Deterministic concurrency behavior of the shared foundation: commands on different evidences of one open case are not
 * globally serialized, while commands on the same evidence serialize and keep the custody chain linked.
 */
class EvidenceCommandConcurrencyIT extends PostgreSqlIntegrationTest {

    @Autowired
    private EvidenceOperationalCommandService commands;

    @Autowired
    private CustodyEventRepository events;

    @Autowired
    private DigitalEvidenceRepository evidences;

    @Autowired
    private CaseMembershipRepository memberships;

    @Autowired
    private CustodyCaseRepository custodyCases;

    @Autowired
    private OperatorRepository operators;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;
    private Operator manager;
    private Operator officer;
    private CustodyCase owningCase;
    private DigitalEvidence first;
    private DigitalEvidence second;

    @BeforeEach
    void setUp() {
        cleanDatabaseInDependencyOrder();
        executor = Executors.newFixedThreadPool(4);
        manager = operators.saveAndFlush(operator("concurrency-manager", OperatorRole.CASE_MANAGER));
        officer = operators.saveAndFlush(operator("concurrency-officer", OperatorRole.EVIDENCE_OFFICER));
        owningCase = custodyCases.saveAndFlush(custodyCase("Concurrency case", manager));
        memberships.saveAndFlush(CaseMembership.assign(owningCase, manager, manager));
        memberships.saveAndFlush(CaseMembership.assign(owningCase, officer, manager));
        first = evidences.saveAndFlush(evidence(owningCase, officer, "CONC.A"));
        second = evidences.saveAndFlush(evidence(owningCase, officer, "CONC.B"));
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
        SecurityContextHolder.clearContext();
        cleanDatabaseInDependencyOrder();
    }

    @Test
    void commandsOnDifferentEvidenceOfTheSameCaseAreNotGloballySerialized() throws Exception {
        CountDownLatch firstInsideLocks = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        Future<EvidenceOperationResponse> blocking = executor.submit(() -> authenticated(
                manager,
                () -> transfer(manager, first.getId(), context -> {
                    firstInsideLocks.countDown();
                    await(releaseFirst);
                })));
        assertThat(firstInsideLocks.await(20, TimeUnit.SECONDS)).isTrue();

        Future<EvidenceOperationResponse> independent =
                executor.submit(() -> authenticated(manager, () -> transfer(manager, second.getId(), context -> {})));

        assertThat(independent.get(20, TimeUnit.SECONDS).eventSummary().sequenceNumber())
                .as("a command on another evidence of the same open case must not wait for the first one")
                .isEqualTo(1L);
        releaseFirst.countDown();
        assertThat(blocking.get(20, TimeUnit.SECONDS).eventSummary().sequenceNumber())
                .isEqualTo(1L);
        assertThat(events.countByEvidenceId(first.getId())).isEqualTo(1);
        assertThat(events.countByEvidenceId(second.getId())).isEqualTo(1);
    }

    @Test
    void commandsOnTheSameEvidenceSerializeAndKeepTheChainLinked() throws Exception {
        CountDownLatch firstInsideLocks = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        Future<EvidenceOperationResponse> blocking = executor.submit(() -> authenticated(
                manager,
                () -> transfer(manager, first.getId(), context -> {
                    firstInsideLocks.countDown();
                    await(releaseFirst);
                })));
        assertThat(firstInsideLocks.await(20, TimeUnit.SECONDS)).isTrue();

        Future<EvidenceOperationResponse> queued =
                executor.submit(() -> authenticated(manager, () -> transfer(manager, first.getId(), context -> {})));
        awaitLockWaiters(1);
        releaseFirst.countDown();

        long blockingSequence =
                blocking.get(20, TimeUnit.SECONDS).eventSummary().sequenceNumber();
        long queuedSequence = queued.get(20, TimeUnit.SECONDS).eventSummary().sequenceNumber();
        List<CustodyEvent> timeline = events.findAllByEvidenceIdOrderBySequenceNumberAsc(first.getId());
        DigitalEvidence reloaded =
                evidences.findByIdForVisibility(first.getId()).orElseThrow();

        assertThat(List.of(blockingSequence, queuedSequence)).containsExactly(1L, 2L);
        assertThat(timeline).extracting(CustodyEvent::getSequenceNumber).containsExactly(1L, 2L);
        assertThat(timeline.getFirst().getPreviousHash()).isEqualTo(CustodyEventHashing.ZERO_HASH);
        assertThat(timeline.getLast().getPreviousHash())
                .isEqualTo(timeline.getFirst().getEventHash());
        assertThat(reloaded.getCustodyEventCount()).isEqualTo(2L);
        assertThat(reloaded.getCustodyChainHeadHash())
                .isEqualTo(timeline.getLast().getEventHash());
        assertThat(reloaded.getUpdatedAt()).isEqualTo(timeline.getLast().getOccurredAt());
    }

    private EvidenceOperationResponse transfer(
            Operator actor, UUID evidenceId, Consumer<EvidenceCommandContext> inside) {
        return commands.execute(EvidenceOperationalCommand.CUSTODY_TRANSFER, evidenceId, principal(actor), context -> {
            inside.accept(context);
            Operator previousHolder = context.evidence().getCurrentHolder();
            UUID previousHolderId = previousHolder.getId();
            Operator newHolder = previousHolderId.equals(manager.getId()) ? managed(officer) : managed(manager);
            context.evidence().transferTo(newHolder);
            return new CustodyTransferredPayload(previousHolderId, newHolder.getId(), "concurrent handover");
        });
    }

    private Operator managed(Operator operator) {
        return operators.findById(operator.getId()).orElseThrow();
    }

    private <T> T authenticated(Operator actor, Supplier<T> action) {
        try {
            SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
            SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken(
                            principal(actor),
                            null,
                            List.of(new SimpleGrantedAuthority(
                                    "ROLE_" + actor.getRole().name()))));
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void awaitLockWaiters(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            Integer waiters = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM pg_stat_activity
                    WHERE datname = current_database()
                      AND pid <> pg_backend_pid()
                      AND wait_event_type = 'Lock'
                    """, Integer.class);
            if (waiters != null && waiters >= expected) {
                return;
            }
        }
        throw new AssertionError("Expected at least " + expected + " PostgreSQL lock waiters");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out while coordinating concurrent operational commands");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while coordinating concurrent operational commands", exception);
        }
    }

    private static AuthenticatedOperator principal(Operator operator) {
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

    private void cleanDatabaseInDependencyOrder() {
        jdbcTemplate.execute("TRUNCATE TABLE custody_events");
        evidences.deleteAllInBatch();
        memberships.deleteAllInBatch();
        custodyCases.deleteAllInBatch();
        operators.deleteAllInBatch();
    }
}
