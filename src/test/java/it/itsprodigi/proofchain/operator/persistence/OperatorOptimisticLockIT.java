package it.itsprodigi.proofchain.operator.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

class OperatorOptimisticLockIT extends PostgreSqlIntegrationTest {

    private static final String BCRYPT_HASH = "$2a$10$01234567890123456789012345678901234567890123456789012";

    @Autowired
    private OperatorRepository repository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void cleanOperators() {
        repository.deleteAll();
    }

    @Test
    void rejectsAStaleUpdateToTheSameOperator() {
        UUID id = repository.saveAndFlush(operator()).getId();

        Operator stale =
                transactionTemplate.execute(status -> repository.findById(id).orElseThrow());
        transactionTemplate.executeWithoutResult(status -> {
            Operator current = repository.findById(id).orElseThrow();
            current.changeEmail("current@example.com");
            repository.flush();
        });

        stale.changeEmail("stale@example.com");
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> repository.saveAndFlush(stale)))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    private static Operator operator() {
        return Operator.create("optimistic", "optimistic@example.com", BCRYPT_HASH, "Jane", "Doe", OperatorRole.ADMIN);
    }
}
