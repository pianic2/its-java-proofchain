package it.itsprodigi.proofchain.custodyevent.application;

import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.custodyCase;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.evidence;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.operator;
import static org.assertj.core.api.Assertions.assertThat;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.custodycase.persistence.CustodyCaseRepository;
import it.itsprodigi.proofchain.custodyevent.api.CustodyChainVerificationResponse;
import it.itsprodigi.proofchain.custodyevent.persistence.CustodyEventRepository;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyTransferredPayload;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceRepository;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves that {@link CustodyChainVerificationService#verifyChain} and {@link CustodyEventAppender#append}
 * are coherent under real concurrent contention on PostgreSQL: verification acquires {@code PESSIMISTIC_READ}
 * ({@code SELECT ... FOR SHARE}) on the evidence row, which the database serializes against the appender's
 * {@code PESSIMISTIC_WRITE} ({@code SELECT ... FOR UPDATE}) on the same row. Whichever transaction reaches
 * the lock first runs to completion before the other proceeds, so verification always observes either a
 * complete before-append snapshot or a complete after-append snapshot, never a torn mix of the two. Uses
 * only a {@link CyclicBarrier} to start both transactions together; no {@code Thread.sleep}.
 */
class CustodyChainVerificationConcurrencyIT extends PostgreSqlIntegrationTest {

    @Autowired
    private CustodyChainVerificationService verificationService;

    @Autowired
    private CustodyEventAppender appender;

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
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        cleanDatabaseInDependencyOrder();
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
        cleanDatabaseInDependencyOrder();
    }

    @Test
    void verificationObservesAnAtomicBeforeOrAfterSnapshotNeverATornChainDuringAConcurrentAppend() throws Exception {
        Operator actor = operators.saveAndFlush(operator("chain-race", OperatorRole.ADMIN));
        CustodyCase custodyCase = custodyCases.saveAndFlush(custodyCase("Race case", actor));
        DigitalEvidence evidence = evidences.saveAndFlush(evidence(custodyCase, actor, "RACE"));
        transactionTemplate.executeWithoutResult(
                status -> appender.append(evidence.getId(), actor, registeredPayload()));
        DigitalEvidence beforeAppend = evidences.findById(evidence.getId()).orElseThrow();
        long countBefore = beforeAppend.getCustodyEventCount();
        String headBefore = beforeAppend.getCustodyChainHeadHash();

        AuthenticatedOperator verifier = new AuthenticatedOperator(
                actor.getId(),
                actor.getUsername(),
                "chain-race@example.com",
                "First",
                "Last",
                actor.getRole(),
                OperatorStatus.ACTIVE,
                Instant.EPOCH,
                Instant.EPOCH);

        CyclicBarrier start = new CyclicBarrier(2);
        Future<CustodyChainVerificationResponse> verification =
                executor.submit(() -> transactionTemplate.execute(status -> {
                    await(start);
                    return verificationService.verifyChain(evidence.getId(), verifier);
                }));
        Future<Void> append = executor.submit(() -> transactionTemplate.execute(status -> {
            await(start);
            appender.append(evidence.getId(), actor, secondPayload());
            return null;
        }));

        CustodyChainVerificationResponse result = get(verification);
        get(append);

        DigitalEvidence afterAppend = evidences.findById(evidence.getId()).orElseThrow();
        assertThat(afterAppend.getCustodyEventCount()).isEqualTo(countBefore + 1);
        assertThat(events.countByEvidenceId(evidence.getId())).isEqualTo(countBefore + 1);

        // The database serializes the two lock holders, so verification must have observed either the
        // complete before-snapshot or the complete after-snapshot: it always self-agrees (checkedEvents ==
        // storedEventCount == loadedEventCount, calculatedHeadHash == storedHeadHash), never a partial mix.
        assertThat(result.valid()).isTrue();
        assertThat(result.checkedEvents()).isEqualTo(result.storedEventCount());
        assertThat(result.loadedEventCount()).isEqualTo(result.storedEventCount());
        assertThat(result.calculatedHeadHash()).isEqualTo(result.storedHeadHash());
        assertThat(result.storedEventCount()).isIn(countBefore, countBefore + 1);
        if (result.storedEventCount() == countBefore) {
            assertThat(result.calculatedHeadHash()).isEqualTo(headBefore);
        } else {
            assertThat(result.calculatedHeadHash()).isEqualTo(afterAppend.getCustodyChainHeadHash());
        }
    }

    private static CustodyTransferredPayload registeredPayload() {
        return new CustodyTransferredPayload(
                UUID.fromString("c0000000-0000-4000-8000-000000000001"),
                UUID.fromString("c0000000-0000-4000-8000-000000000002"),
                "Initial transfer");
    }

    private static CustodyTransferredPayload secondPayload() {
        return new CustodyTransferredPayload(
                UUID.fromString("c0000000-0000-4000-8000-000000000002"),
                UUID.fromString("c0000000-0000-4000-8000-000000000001"),
                "Concurrent transfer");
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating chain verification race", exception);
        } catch (BrokenBarrierException | TimeoutException exception) {
            throw new IllegalStateException("Timed out while coordinating chain verification race", exception);
        }
    }

    private static <T> T get(Future<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private void cleanDatabaseInDependencyOrder() {
        jdbcTemplate.execute("TRUNCATE TABLE custody_events");
        evidences.deleteAllInBatch();
        memberships.deleteAllInBatch();
        custodyCases.deleteAllInBatch();
        operators.deleteAllInBatch();
    }
}
