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
}
