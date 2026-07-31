package it.itsprodigi.proofchain.custodycase.persistence;

import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CustodyCaseRepository extends JpaRepository<CustodyCase, UUID> {

    @Transactional(readOnly = true)
    @EntityGraph(attributePaths = "createdBy")
    @Query("SELECT custodyCase FROM CustodyCase custodyCase WHERE custodyCase.id = :caseId")
    Optional<CustodyCase> findByIdWithCreatedBy(@Param("caseId") UUID caseId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT custodyCase FROM CustodyCase custodyCase WHERE custodyCase.id = :caseId")
    Optional<CustodyCase> findByIdForUpdate(@Param("caseId") UUID caseId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT custodyCase FROM CustodyCase custodyCase WHERE custodyCase.id = :caseId")
    Optional<CustodyCase> findByIdForShare(@Param("caseId") UUID caseId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT custodyCase FROM CustodyCase custodyCase WHERE custodyCase.id IN :caseIds ORDER BY custodyCase.id")
    List<CustodyCase> lockAllByIdInOrderById(@Param("caseIds") List<UUID> caseIds);

    @Transactional(readOnly = true)
    @EntityGraph(attributePaths = "createdBy")
    @Query(
            value =
                    "SELECT custodyCase FROM CustodyCase custodyCase ORDER BY custodyCase.createdAt DESC, custodyCase.id ASC",
            countQuery = "SELECT COUNT(custodyCase) FROM CustodyCase custodyCase")
    Page<CustodyCase> findPageForAdmin(Pageable pageable);

    @Transactional(readOnly = true)
    @EntityGraph(attributePaths = "createdBy")
    @Query(value = """
                    SELECT custodyCase
                    FROM CaseMembership membership
                    JOIN membership.custodyCase custodyCase
                    WHERE membership.operator.id = :operatorId
                    ORDER BY custodyCase.createdAt DESC, custodyCase.id ASC
                    """, countQuery = """
                    SELECT COUNT(membership)
                    FROM CaseMembership membership
                    WHERE membership.operator.id = :operatorId
                    """)
    Page<CustodyCase> findPageForMember(@Param("operatorId") UUID operatorId, Pageable pageable);
}
