package it.itsprodigi.proofchain.custodyevent.persistence;

import it.itsprodigi.proofchain.custodyevent.domain.CustodyEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CustodyEventRepository extends Repository<CustodyEvent, UUID> {

    <S extends CustodyEvent> S save(S event);

    @Transactional(readOnly = true)
    @EntityGraph(attributePaths = {"custodyCase", "evidence", "operator"})
    List<CustodyEvent> findAllByEvidenceIdOrderBySequenceNumberAsc(UUID evidenceId);

    @Transactional(readOnly = true)
    @EntityGraph(attributePaths = {"custodyCase", "evidence", "operator"})
    Page<CustodyEvent> findAllByEvidenceIdOrderBySequenceNumberAsc(UUID evidenceId, Pageable pageable);

    @Transactional(readOnly = true)
    @EntityGraph(attributePaths = {"custodyCase", "evidence", "operator"})
    @Query("SELECT event FROM CustodyEvent event WHERE event.evidence.id = :evidenceId AND event.id = :eventId")
    Optional<CustodyEvent> findByEvidenceIdAndEventId(
            @Param("evidenceId") UUID evidenceId, @Param("eventId") UUID eventId);

    @Transactional(readOnly = true)
    long countByEvidenceId(UUID evidenceId);

    @Transactional(readOnly = true)
    Optional<CustodyEvent> findFirstByEvidenceIdOrderBySequenceNumberAsc(UUID evidenceId);

    @Transactional(readOnly = true)
    Optional<CustodyEvent> findFirstByEvidenceIdOrderBySequenceNumberDesc(UUID evidenceId);
}
