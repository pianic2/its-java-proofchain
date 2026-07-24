package it.itsprodigi.proofchain.operator.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

class ActiveAdminLockIT extends PostgreSqlIntegrationTest {

    private static final String BCRYPT_HASH = "$2a$10$01234567890123456789012345678901234567890123456789012";

    @Autowired
    private OperatorRepository repository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        repository.saveAllAndFlush(List.of(operator("admin-one"), operator("admin-two")));
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void serializesCompetingActiveAdminLocksInUuidOrder() throws Exception {
        CountDownLatch firstHasLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttemptedLock = new CountDownLatch(1);

        Future<List<UUID>> first = executor.submit(() -> transactionTemplate.execute(status -> {
            List<UUID> ids = ids(repository.lockActiveAdmins());
            firstHasLock.countDown();
            await(releaseFirst);
            return ids;
        }));

        assertThat(firstHasLock.await(5, TimeUnit.SECONDS)).isTrue();
        Future<List<UUID>> second = executor.submit(() -> transactionTemplate.execute(status -> {
            secondAttemptedLock.countDown();
            return ids(repository.lockActiveAdmins());
        }));

        assertThat(secondAttemptedLock.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        } finally {
            releaseFirst.countDown();
        }

        List<UUID> firstIds = first.get(5, TimeUnit.SECONDS);
        List<UUID> secondIds = second.get(5, TimeUnit.SECONDS);
        List<UUID> sortedIds = new ArrayList<>(firstIds);
        sortedIds.sort(Comparator.comparing(UUID::toString));

        assertThat(firstIds).containsExactlyElementsOf(sortedIds);
        assertThat(secondIds).containsExactlyElementsOf(sortedIds);
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

    private static List<UUID> ids(List<Operator> operators) {
        return operators.stream().map(Operator::getId).toList();
    }

    private static Operator operator(String username) {
        return Operator.create(username, username + "@example.com", BCRYPT_HASH, "Jane", "Doe", OperatorRole.ADMIN);
    }
}
