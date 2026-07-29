package it.itsprodigi.proofchain.custodycase.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.itsprodigi.proofchain.custodycase.domain.CasePriority;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class CustodyCaseLockIT extends PostgreSqlIntegrationTest {

    private static final String BCRYPT_HASH = "$2a$10$01234567890123456789012345678901234567890123456789012";

    @Autowired
    private CaseMembershipRepository caseMembershipRepository;

    @Autowired
    private CustodyCaseRepository custodyCaseRepository;

    @Autowired
    private OperatorRepository operatorRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

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

    private void cleanDatabaseInDependencyOrder() {
        caseMembershipRepository.deleteAllInBatch();
        custodyCaseRepository.deleteAllInBatch();
        operatorRepository.deleteAllInBatch();
    }

    @Test
    void serializesCompetingPessimisticCaseLocks() throws Exception {
        Operator owner = operatorRepository.saveAndFlush(
                Operator.create("owner", "owner@example.com", BCRYPT_HASH, "Jane", "Doe", OperatorRole.ADMIN));
        UUID caseId = custodyCaseRepository
                .saveAndFlush(CustodyCase.create("Locked case", null, null, null, null, CasePriority.HIGH, owner))
                .getId();
        CountDownLatch firstHasLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttemptedLock = new CountDownLatch(1);

        Future<UUID> first = executor.submit(() -> transactionTemplate.execute(status -> {
            UUID lockedId = custodyCaseRepository
                    .findByIdForUpdate(caseId)
                    .orElseThrow()
                    .getId();
            firstHasLock.countDown();
            await(releaseFirst);
            return lockedId;
        }));

        assertThat(firstHasLock.await(5, TimeUnit.SECONDS)).isTrue();
        Future<UUID> second = executor.submit(() -> transactionTemplate.execute(status -> {
            secondAttemptedLock.countDown();
            return custodyCaseRepository.findByIdForUpdate(caseId).orElseThrow().getId();
        }));

        assertThat(secondAttemptedLock.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        } finally {
            releaseFirst.countDown();
        }

        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(caseId);
        assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(caseId);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out while coordinating lock test");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating lock test", exception);
        }
    }
}
