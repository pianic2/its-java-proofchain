package it.itsprodigi.proofchain.operator.persistence;

import it.itsprodigi.proofchain.operator.domain.Operator;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface OperatorRepository extends JpaRepository<Operator, UUID> {

    @Transactional(readOnly = true)
    Optional<Operator> findByUsername(String normalizedUsername);

    boolean existsByUsername(String normalizedUsername);

    boolean existsByEmail(String normalizedEmail);

    @Query("""
            SELECT COUNT(operator)
            FROM Operator operator
            WHERE operator.role = it.itsprodigi.proofchain.operator.domain.OperatorRole.ADMIN
              AND operator.status = it.itsprodigi.proofchain.operator.domain.OperatorStatus.ACTIVE
            """)
    long countActiveAdmins();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT operator
            FROM Operator operator
            WHERE operator.role = it.itsprodigi.proofchain.operator.domain.OperatorRole.ADMIN
              AND operator.status = it.itsprodigi.proofchain.operator.domain.OperatorStatus.ACTIVE
            ORDER BY operator.id
            """)
    List<Operator> lockActiveAdmins();
}
