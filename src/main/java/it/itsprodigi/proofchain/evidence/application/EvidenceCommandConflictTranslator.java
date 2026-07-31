package it.itsprodigi.proofchain.evidence.application;

import it.itsprodigi.proofchain.custodyevent.application.CustodyEventConcurrencyConflictException;
import it.itsprodigi.proofchain.custodyevent.application.CustodyEventPersistenceFailureException;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PessimisticLockException;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Component;

/**
 * Translates technical write conflicts into stable Problem Detail carriers.
 *
 * <p>Lock timeouts, deadlocks, optimistic conflicts and custody chain append collisions all become {@link
 * CustodyEventConcurrencyConflictException}. Nothing is retried, and no SQL state, lock owner, persistence exception or
 * stack trace detail is exposed to callers.
 */
@Component
public class EvidenceCommandConflictTranslator {

    public <T> T translating(Supplier<T> command) {
        Objects.requireNonNull(command, "command must not be null");
        try {
            return command.get();
        } catch (CustodyEventConcurrencyConflictException exception) {
            throw exception;
        } catch (ConcurrencyFailureException
                | QueryTimeoutException
                | OptimisticLockException
                | PessimisticLockException
                | LockTimeoutException exception) {
            throw new CustodyEventConcurrencyConflictException(exception);
        } catch (DataAccessException exception) {
            throw new CustodyEventPersistenceFailureException(exception);
        }
    }
}
