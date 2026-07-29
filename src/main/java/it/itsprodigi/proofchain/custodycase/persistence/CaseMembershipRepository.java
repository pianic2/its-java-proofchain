package it.itsprodigi.proofchain.custodycase.persistence;

import it.itsprodigi.proofchain.custodycase.domain.CaseMembership;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CaseMembershipRepository extends JpaRepository<CaseMembership, UUID> {

    @Transactional(readOnly = true)
    @EntityGraph(attributePaths = {"operator", "assignedBy"})
    @Query("""
            SELECT membership
            FROM CaseMembership membership
            WHERE membership.custodyCase.id = :caseId
              AND membership.operator.id = :operatorId
            """)
    Optional<CaseMembership> findByCaseIdAndOperatorId(
            @Param("caseId") UUID caseId, @Param("operatorId") UUID operatorId);

    boolean existsByCustodyCaseIdAndOperatorId(UUID caseId, UUID operatorId);

    @Transactional(readOnly = true)
    @EntityGraph(attributePaths = {"operator", "assignedBy"})
    List<CaseMembership> findAllByCustodyCaseIdOrderByAssignedAtAscIdAsc(UUID caseId);

    @Transactional(readOnly = true)
    @Query("""
            SELECT membership.custodyCase.id
            FROM CaseMembership membership
            WHERE membership.operator.id = :operatorId
            ORDER BY membership.custodyCase.id
            """)
    List<UUID> findCaseIdsByOperatorIdOrderByCaseId(@Param("operatorId") UUID operatorId);

    @Transactional(readOnly = true)
    @Query("""
            SELECT COUNT(membership)
            FROM CaseMembership membership
            WHERE membership.custodyCase.id = :caseId
              AND membership.operator.status = it.itsprodigi.proofchain.operator.domain.OperatorStatus.ACTIVE
              AND membership.operator.role IN (
                  it.itsprodigi.proofchain.operator.domain.OperatorRole.ADMIN,
                  it.itsprodigi.proofchain.operator.domain.OperatorRole.CASE_MANAGER
              )
            """)
    long countResponsibleManagers(@Param("caseId") UUID caseId);

    @Transactional(readOnly = true)
    @Query("""
            SELECT membership.custodyCase.id AS caseId, COUNT(membership) AS responsibleCount
            FROM CaseMembership membership
            WHERE membership.custodyCase.id IN :caseIds
              AND membership.operator.id <> :excludedOperatorId
              AND membership.operator.status = it.itsprodigi.proofchain.operator.domain.OperatorStatus.ACTIVE
              AND membership.operator.role IN (
                  it.itsprodigi.proofchain.operator.domain.OperatorRole.ADMIN,
                  it.itsprodigi.proofchain.operator.domain.OperatorRole.CASE_MANAGER
              )
            GROUP BY membership.custodyCase.id
            """)
    List<ResponsibleManagerCount> countOtherResponsibleManagersByCase(
            @Param("caseIds") List<UUID> caseIds, @Param("excludedOperatorId") UUID excludedOperatorId);

    interface ResponsibleManagerCount {
        UUID getCaseId();

        long getResponsibleCount();
    }
}
