package it.itsprodigi.proofchain.evidence.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.itsprodigi.proofchain.custodyevent.application.CustodyEventConcurrencyConflictException;
import it.itsprodigi.proofchain.custodyevent.application.CustodyEventPersistenceFailureException;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PessimisticLockException;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;

class EvidenceCommandConflictTranslatorTest {

    private final EvidenceCommandConflictTranslator translator = new EvidenceCommandConflictTranslator();

    static Stream<Arguments> writeConflicts() {
        return Stream.of(
                Arguments.of(new PessimisticLockingFailureException("lock timeout on 42P01")),
                Arguments.of(new CannotAcquireLockException("could not obtain lock on row")),
                Arguments.of(new DeadlockLoserDataAccessException("deadlock detected", new SQLException("40P01"))),
                Arguments.of(new OptimisticLockingFailureException("row was updated by another transaction")),
                Arguments.of(new QueryTimeoutException("statement timeout")),
                Arguments.of(new OptimisticLockException("stale entity")),
                Arguments.of(new PessimisticLockException("row locked")),
                Arguments.of(new LockTimeoutException("lock wait timeout")),
                Arguments.of(new CustodyEventConcurrencyConflictException(new IllegalStateException("chain race"))));
    }

    @ParameterizedTest
    @MethodSource("writeConflicts")
    void everyWriteConflictBecomesTheStableConcurrencyConflict(RuntimeException failure) {
        assertThatThrownBy(() -> translator.translating(() -> {
                    throw failure;
                }))
                .isInstanceOf(CustodyEventConcurrencyConflictException.class)
                .hasMessage("A concurrent custody event append prevented the operation");
    }

    @Test
    void conflictsAreNeverRetried() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> translator.translating(() -> {
                    attempts.incrementAndGet();
                    throw new CannotAcquireLockException("lock timeout");
                }))
                .isInstanceOf(CustodyEventConcurrencyConflictException.class);

        assertThat(attempts).hasValue(1);
    }

    @Test
    void otherPersistenceFailuresBecomeAStableCustodyEventPersistenceFailure() {
        assertThatThrownBy(() -> translator.translating(() -> {
                    throw new DataIntegrityViolationException("ERROR: null value in column \"event_hash\"");
                }))
                .isInstanceOf(CustodyEventPersistenceFailureException.class)
                .hasMessage("The custody event could not be persisted");
    }

    @Test
    void businessFailuresArePropagatedUnchanged() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> translator.translating(() -> {
                    attempts.incrementAndGet();
                    throw new InvalidEvidenceStateException("Released evidence is terminal and cannot be modified.");
                }))
                .isInstanceOf(InvalidEvidenceStateException.class);

        assertThat(attempts).hasValue(1);
        assertThat(translator.translating(() -> "value")).isEqualTo("value");
    }
}
