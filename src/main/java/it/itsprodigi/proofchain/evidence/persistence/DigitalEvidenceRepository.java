package it.itsprodigi.proofchain.evidence.persistence;

import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidenceNormalizer;
import jakarta.persistence.LockModeType;
import java.util.Objects;
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

public interface DigitalEvidenceRepository extends JpaRepository<DigitalEvidence, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT evidence FROM DigitalEvidence evidence WHERE evidence.id = :evidenceId")
    Optional<DigitalEvidence> findByIdForCustodyEventAppend(@Param("evidenceId") UUID evidenceId);

    default boolean existsByCaseIdAndReferenceTag(UUID caseId, String referenceTag) {
        Objects.requireNonNull(caseId, "caseId must not be null");
        String normalizedReferenceTag = DigitalEvidenceNormalizer.normalizeReferenceTag(referenceTag);
        return normalizedReferenceTag != null
                && existsByCaseIdAndNormalizedReferenceTag(caseId, normalizedReferenceTag);
    }

    default Optional<DigitalEvidence> findByCaseIdAndReferenceTag(UUID caseId, String referenceTag) {
        Objects.requireNonNull(caseId, "caseId must not be null");
        String normalizedReferenceTag = DigitalEvidenceNormalizer.normalizeReferenceTag(referenceTag);
        return normalizedReferenceTag == null
                ? Optional.empty()
                : findByCaseIdAndNormalizedReferenceTag(caseId, normalizedReferenceTag);
    }

    @Transactional(readOnly = true)
    @Query("""
            SELECT CASE WHEN COUNT(evidence) > 0 THEN true ELSE false END
            FROM DigitalEvidence evidence
            WHERE evidence.custodyCase.id = :caseId
              AND evidence.referenceTag = :referenceTag
            """)
    boolean existsByCaseIdAndNormalizedReferenceTag(
            @Param("caseId") UUID caseId, @Param("referenceTag") String referenceTag);

    @Transactional(readOnly = true)
    @Query("""
            SELECT evidence
            FROM DigitalEvidence evidence
            WHERE evidence.custodyCase.id = :caseId
              AND evidence.referenceTag = :referenceTag
            """)
    Optional<DigitalEvidence> findByCaseIdAndNormalizedReferenceTag(
            @Param("caseId") UUID caseId, @Param("referenceTag") String referenceTag);

    @Transactional(readOnly = true)
    @Query(value = """
                    SELECT evidence
                    FROM DigitalEvidence evidence
                    JOIN FETCH evidence.custodyCase
                    LEFT JOIN FETCH evidence.currentHolder
                    JOIN FETCH evidence.uploadedBy
                    WHERE evidence.custodyCase.id = :caseId
                    ORDER BY evidence.createdAt DESC, evidence.id ASC
                    """, countQuery = """
                    SELECT COUNT(evidence)
                    FROM DigitalEvidence evidence
                    WHERE evidence.custodyCase.id = :caseId
                    """)
    Page<DigitalEvidence> findPageByCaseId(@Param("caseId") UUID caseId, Pageable pageable);

    @Transactional(readOnly = true)
    @EntityGraph(attributePaths = {"custodyCase", "currentHolder", "uploadedBy"})
    @Query("SELECT evidence FROM DigitalEvidence evidence WHERE evidence.id = :evidenceId")
    Optional<DigitalEvidence> findByIdForVisibility(@Param("evidenceId") UUID evidenceId);

    @Transactional(readOnly = true)
    @Query("""
            SELECT evidence.id AS evidenceId,
                   evidence.custodyCase.id AS caseId,
                   evidence.originalFilename AS originalFilename,
                   evidence.mediaType AS mediaType,
                   evidence.fileSize AS fileSize,
                   evidence.contentSha256 AS contentSha256,
                   evidence.contextualSha256 AS contextualSha256,
                   evidence.storageKey AS storageKey
            FROM DigitalEvidence evidence
            WHERE evidence.id = :evidenceId
            """)
    Optional<DigitalEvidenceContentMetadata> findContentMetadataById(@Param("evidenceId") UUID evidenceId);
}
