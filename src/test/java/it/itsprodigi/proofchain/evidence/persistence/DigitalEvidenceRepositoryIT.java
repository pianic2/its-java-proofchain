package it.itsprodigi.proofchain.evidence.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.itsprodigi.proofchain.custodycase.domain.CasePriority;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.custodycase.persistence.CustodyCaseRepository;
import it.itsprodigi.proofchain.evidence.domain.AcquisitionMethod;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.domain.SourceType;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceUnitUtil;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class DigitalEvidenceRepositoryIT extends PostgreSqlIntegrationTest {

    private static final String BCRYPT_HASH = "$2a$10$01234567890123456789012345678901234567890123456789012";
    private static final String CONTENT_SHA_256 = "a".repeat(64);
    private static final String CONTEXTUAL_SHA_256 = "b".repeat(64);
    private static final String STORAGE_KEY = "cases/case-id/evidences/evidence-id/content.bin";

    @Autowired
    private DigitalEvidenceRepository evidenceRepository;

    @Autowired
    private CaseMembershipRepository membershipRepository;

    @Autowired
    private CustodyCaseRepository custodyCaseRepository;

    @Autowired
    private OperatorRepository operatorRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void cleanDatabase() {
        cleanDatabaseInDependencyOrder();
    }

    @AfterEach
    void cleanDatabaseAfterTest() {
        cleanDatabaseInDependencyOrder();
    }

    @Test
    void flywayCreatesTheSchemaIndexesAndNoCascadeForeignKeysThatHibernateValidates() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT success FROM flyway_schema_history WHERE version = '5'", Boolean.class))
                .isTrue();

        Map<String, ColumnDefinition> columns = columnsFor("digital_evidence");
        assertThat(columns)
                .containsEntry("id", new ColumnDefinition("uuid", null, null))
                .containsEntry("case_id", new ColumnDefinition("uuid", null, null))
                .containsEntry("reference_tag", new ColumnDefinition("character varying", 64, null))
                .containsEntry("status", new ColumnDefinition("character varying", 16, null))
                .containsEntry("source_type", new ColumnDefinition("character varying", 32, null))
                .containsEntry("acquisition_method", new ColumnDefinition("character varying", 32, null))
                .containsEntry("acquired_at", new ColumnDefinition("timestamp with time zone", null, 6))
                .containsEntry("file_size", new ColumnDefinition("bigint", null, null))
                .containsEntry("content_sha256", new ColumnDefinition("character varying", 64, null))
                .containsEntry("contextual_sha256", new ColumnDefinition("character varying", 64, null))
                .containsEntry("custody_event_count", new ColumnDefinition("bigint", null, null))
                .containsEntry("custody_chain_head_hash", new ColumnDefinition("character", 64, null))
                .containsEntry("created_at", new ColumnDefinition("timestamp with time zone", null, 6))
                .containsEntry("updated_at", new ColumnDefinition("timestamp with time zone", null, 6))
                .containsEntry("version", new ColumnDefinition("bigint", null, null));
        assertThat(columns.keySet())
                .containsExactlyInAnyOrder(
                        "id",
                        "case_id",
                        "reference_tag",
                        "title",
                        "description",
                        "status",
                        "current_holder_operator_id",
                        "uploaded_by_operator_id",
                        "source_type",
                        "source_description",
                        "source_manufacturer",
                        "source_model",
                        "source_serial_number",
                        "source_logical_identifier",
                        "acquisition_method",
                        "acquisition_location",
                        "acquisition_tool_name",
                        "acquisition_tool_version",
                        "acquisition_notes",
                        "acquired_at",
                        "original_filename",
                        "file_extension",
                        "media_type",
                        "file_size",
                        "content_sha256",
                        "contextual_sha256",
                        "storage_key",
                        "custody_event_count",
                        "custody_chain_head_hash",
                        "created_at",
                        "updated_at",
                        "version");

        assertThat(constraintsFor("digital_evidence"))
                .contains(
                        "pk_digital_evidence",
                        "fk_digital_evidence_case",
                        "fk_digital_evidence_current_holder",
                        "fk_digital_evidence_uploaded_by",
                        "ck_digital_evidence_reference_tag",
                        "ck_digital_evidence_status_holder",
                        "ck_digital_evidence_source_type",
                        "ck_digital_evidence_acquisition_method",
                        "ck_digital_evidence_file_size",
                        "ck_digital_evidence_content_sha256",
                        "ck_digital_evidence_contextual_sha256",
                        "ck_digital_evidence_storage_key",
                        "ck_digital_evidence_custody_event_count",
                        "ck_digital_evidence_custody_chain_head_hash",
                        "ck_digital_evidence_custody_chain_empty_head",
                        "ck_digital_evidence_version_non_negative");

        Map<String, String> deleteActions = jdbcTemplate
                .query("""
                        SELECT conname, confdeltype::text
                        FROM pg_constraint
                        WHERE conrelid = 'digital_evidence'::regclass AND contype = 'f'
                        """, (resultSet, rowNumber) -> Map.entry(resultSet.getString(1), resultSet.getString(2)))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(deleteActions)
                .containsOnly(
                        Map.entry("fk_digital_evidence_case", "a"),
                        Map.entry("fk_digital_evidence_current_holder", "a"),
                        Map.entry("fk_digital_evidence_uploaded_by", "a"));

        assertThat(indexDefinition("uk_digital_evidence_case_reference_tag"))
                .contains("(case_id, reference_tag)")
                .contains("WHERE (reference_tag IS NOT NULL)");
        assertThat(indexDefinition("ix_digital_evidence_case_created_at_id"))
                .contains("(case_id, created_at DESC, id)");
        assertThat(indexDefinition("ix_digital_evidence_current_holder_operator_id"))
                .contains("(current_holder_operator_id)");
        assertThat(indexDefinition("ix_digital_evidence_uploaded_by_operator_id"))
                .contains("(uploaded_by_operator_id)");
        assertThat(indexDefinition("ix_digital_evidence_content_sha256")).contains("(content_sha256)");
        assertThat(indexDefinition("ix_digital_evidence_status")).contains("(status)");
        assertThat(indexDefinition("ix_digital_evidence_source_type")).contains("(source_type)");
        assertThat(indexDefinition("ix_digital_evidence_acquisition_method")).contains("(acquisition_method)");
        assertThat(indexDefinition("ix_digital_evidence_created_at_id")).contains("(created_at DESC, id)");
    }

    @Test
    void persistsUuidV4MicrosecondsLazyAssociationsAndSupportsFetchGraphAndContentProjection() {
        EvidenceContext context = saveEvidence("persistence", "  evidence-01  ");
        DigitalEvidence saved = context.evidence();
        UUID evidenceId = saved.getId();
        Instant createdAt = saved.getCreatedAt();

        assertThat(evidenceId.version()).isEqualTo(4);
        assertThat(createdAt).isEqualTo(createdAt.truncatedTo(ChronoUnit.MICROS));
        assertThat(saved.getUpdatedAt()).isEqualTo(createdAt);
        assertThat(evidenceRepository.existsByCaseIdAndReferenceTag(
                        context.custodyCase().getId(), " evidence-01 "))
                .isTrue();
        assertThat(evidenceRepository.findByCaseIdAndReferenceTag(
                        context.custodyCase().getId(), " evidence-01 "))
                .get()
                .extracting(DigitalEvidence::getId)
                .isEqualTo(evidenceId);

        entityManager.clear();
        DigitalEvidence regular = evidenceRepository.findById(evidenceId).orElseThrow();
        PersistenceUnitUtil persistenceUnitUtil =
                entityManager.getEntityManagerFactory().getPersistenceUnitUtil();
        assertThat(persistenceUnitUtil.isLoaded(regular, "custodyCase")).isFalse();
        assertThat(persistenceUnitUtil.isLoaded(regular, "currentHolder")).isFalse();
        assertThat(persistenceUnitUtil.isLoaded(regular, "uploadedBy")).isFalse();
        assertThat(regular.getCreatedAt()).isEqualTo(createdAt);

        entityManager.clear();
        DigitalEvidence visible =
                evidenceRepository.findByIdForVisibility(evidenceId).orElseThrow();
        assertThat(persistenceUnitUtil.isLoaded(visible, "custodyCase")).isTrue();
        assertThat(persistenceUnitUtil.isLoaded(visible, "currentHolder")).isTrue();
        assertThat(persistenceUnitUtil.isLoaded(visible, "uploadedBy")).isTrue();

        DigitalEvidenceContentMetadata content =
                evidenceRepository.findContentMetadataById(evidenceId).orElseThrow();
        assertThat(content.getEvidenceId()).isEqualTo(evidenceId);
        assertThat(content.getCaseId()).isEqualTo(context.custodyCase().getId());
        assertThat(content.getOriginalFilename()).isEqualTo("disk-image.E01");
        assertThat(content.getMediaType()).isEqualTo("application/octet-stream");
        assertThat(content.getFileSize()).isEqualTo(4096L);
        assertThat(content.getContentSha256()).isEqualTo(CONTENT_SHA_256);
        assertThat(content.getContextualSha256()).isEqualTo(CONTEXTUAL_SHA_256);
        assertThat(content.getStorageKey()).isEqualTo(STORAGE_KEY);
    }

    @Test
    void databaseRejectsInvalidEnumsHashesSizeHolderStateAcquisitionPathFilenameAndVersion() {
        UUID evidenceId = saveEvidence("constraints", "TAG-01").evidence().getId();

        assertDatabaseRejects("UPDATE digital_evidence SET status = 'ARCHIVED' WHERE id = ?", evidenceId);
        assertDatabaseRejects("UPDATE digital_evidence SET source_type = 'PHONE' WHERE id = ?", evidenceId);
        assertDatabaseRejects("UPDATE digital_evidence SET acquisition_method = 'COPY' WHERE id = ?", evidenceId);
        assertDatabaseRejects(
                "UPDATE digital_evidence SET content_sha256 = ? WHERE id = ?", "A".repeat(64), evidenceId);
        assertDatabaseRejects("UPDATE digital_evidence SET contextual_sha256 = ? WHERE id = ?", "short", evidenceId);
        assertDatabaseRejects("UPDATE digital_evidence SET file_size = 0 WHERE id = ?", evidenceId);
        assertDatabaseRejects("UPDATE digital_evidence SET current_holder_operator_id = NULL WHERE id = ?", evidenceId);
        assertDatabaseRejects("UPDATE digital_evidence SET status = 'RELEASED' WHERE id = ?", evidenceId);
        assertDatabaseRejects(
                "UPDATE digital_evidence SET acquired_at = created_at + INTERVAL '1 second' WHERE id = ?", evidenceId);
        assertDatabaseRejects("UPDATE digital_evidence SET storage_key = '../escape' WHERE id = ?", evidenceId);
        assertDatabaseRejects("UPDATE digital_evidence SET original_filename = '../escape' WHERE id = ?", evidenceId);
        assertDatabaseRejects("UPDATE digital_evidence SET custody_event_count = -1 WHERE id = ?", evidenceId);
        assertDatabaseRejects(
                "UPDATE digital_evidence SET custody_chain_head_hash = ? WHERE id = ?", "A".repeat(64), evidenceId);
        assertDatabaseRejects(
                "UPDATE digital_evidence SET custody_chain_head_hash = ? WHERE id = ?", "c".repeat(64), evidenceId);
        assertDatabaseRejects("UPDATE digital_evidence SET version = -1 WHERE id = ?", evidenceId);
    }

    /**
     * The lifecycle graph is enforced by the database itself, not only by the aggregate and the application services:
     * only IN_CUSTODY -&gt; SEALED, IN_CUSTODY -&gt; RELEASED and SEALED -&gt; RELEASED are accepted, RELEASED is
     * terminal, and the status/holder check makes it impossible to give released evidence a holder again.
     */
    @Test
    void databaseEnforcesTheExactEvidenceLifecycleGraphAndTerminalReleasedState() {
        EvidenceContext context = saveEvidence("lifecycle", "LIFECYCLE-01");
        UUID evidenceId = context.evidence().getId();
        UUID holderId = context.holder().getId();

        assertThat(jdbcTemplate.update("UPDATE digital_evidence SET status = 'SEALED' WHERE id = ?", evidenceId))
                .isOne();
        assertDatabaseRejects("UPDATE digital_evidence SET status = 'IN_CUSTODY' WHERE id = ?", evidenceId);
        assertThat(jdbcTemplate.update("""
                        UPDATE digital_evidence
                        SET status = 'RELEASED', current_holder_operator_id = NULL
                        WHERE id = ?
                        """, evidenceId)).isOne();

        for (String terminalBreach : new String[] {"IN_CUSTODY", "SEALED"}) {
            assertDatabaseRejects(
                    "UPDATE digital_evidence SET status = ?, current_holder_operator_id = ? WHERE id = ?",
                    terminalBreach,
                    holderId,
                    evidenceId);
        }
        assertDatabaseRejects(
                "UPDATE digital_evidence SET current_holder_operator_id = ? WHERE id = ?", holderId, evidenceId);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM digital_evidence WHERE id = ?", String.class, evidenceId))
                .isEqualTo("RELEASED");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT current_holder_operator_id FROM digital_evidence WHERE id = ?", UUID.class, evidenceId))
                .isNull();
    }

    /** IN_CUSTODY may go straight to RELEASED without ever being sealed. */
    @Test
    void databaseAcceptsTheDirectInCustodyToReleasedEdge() {
        UUID evidenceId =
                saveEvidence("direct-release", "LIFECYCLE-02").evidence().getId();

        assertThat(jdbcTemplate.update("""
                        UPDATE digital_evidence
                        SET status = 'RELEASED', current_holder_operator_id = NULL
                        WHERE id = ?
                        """, evidenceId)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM digital_evidence WHERE id = ?", String.class, evidenceId))
                .isEqualTo("RELEASED");
    }

    @Test
    void referenceTagIsUniqueWithinOneCaseButReusableAcrossCasesAndQueriesNormalizeIt() {
        EvidenceContext first = saveEvidence("unique", "  tag-01  ");
        CustodyCase otherCase =
                custodyCaseRepository.saveAndFlush(custodyCase("Other evidence case", first.uploader()));
        DigitalEvidence crossCase = evidenceRepository.saveAndFlush(
                evidence(otherCase, first.holder(), first.uploader(), "tag-01", "Cross-case evidence"));

        assertThat(evidenceRepository.existsByCaseIdAndReferenceTag(
                        first.custodyCase().getId(), " tag-01 "))
                .isTrue();
        assertThat(evidenceRepository.findByCaseIdAndReferenceTag(otherCase.getId(), " tag-01 "))
                .contains(crossCase);
        assertThat(evidenceRepository.existsByCaseIdAndReferenceTag(
                        first.custodyCase().getId(), "  "))
                .isFalse();
        assertThat(evidenceRepository.findByCaseIdAndReferenceTag(
                        first.custodyCase().getId(), null))
                .isEmpty();

        DigitalEvidence duplicate =
                evidence(first.custodyCase(), first.holder(), first.uploader(), " TAG-01 ", "Duplicate evidence");
        assertThatThrownBy(() -> evidenceRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
        entityManager.clear();
    }

    @Test
    void pagesOneCaseUsingCreatedAtDescendingThenIdAscending() {
        Operator uploader = saveOperator("page-uploader", OperatorRole.ADMIN);
        Operator holder = saveOperator("page-holder", OperatorRole.CASE_MANAGER);
        CustodyCase custodyCase = custodyCaseRepository.saveAndFlush(custodyCase("Paged evidence case", uploader));
        DigitalEvidence olderHigh =
                evidenceRepository.saveAndFlush(evidence(custodyCase, holder, uploader, "TAG-03", "Older high"));
        DigitalEvidence olderLow =
                evidenceRepository.saveAndFlush(evidence(custodyCase, holder, uploader, "TAG-02", "Older low"));
        DigitalEvidence newest =
                evidenceRepository.saveAndFlush(evidence(custodyCase, holder, uploader, "TAG-01", "Newest"));

        UUID olderLowId = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID olderHighId = UUID.fromString("00000000-0000-4000-8000-000000000002");
        UUID newestId = UUID.fromString("00000000-0000-4000-8000-000000000003");
        Instant olderTime = Instant.parse("2026-07-29T08:00:00Z");
        Instant newerTime = Instant.parse("2026-07-29T09:00:00Z");
        replaceIdentityAndTimestamp(olderHigh.getId(), olderHighId, olderTime);
        replaceIdentityAndTimestamp(olderLow.getId(), olderLowId, olderTime);
        replaceIdentityAndTimestamp(newest.getId(), newestId, newerTime);
        entityManager.clear();

        Page<DigitalEvidence> firstPage =
                evidenceRepository.findPageByCaseId(custodyCase.getId(), PageRequest.of(0, 2));
        Page<DigitalEvidence> secondPage =
                evidenceRepository.findPageByCaseId(custodyCase.getId(), PageRequest.of(1, 2));

        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getContent()).extracting(DigitalEvidence::getId).containsExactly(newestId, olderLowId);
        assertThat(secondPage.getContent()).extracting(DigitalEvidence::getId).containsExactly(olderHighId);
    }

    @Test
    void rejectsAnOptimisticVersionConflict() {
        UUID evidenceId = saveEvidence("locking", "TAG-01").evidence().getId();
        DigitalEvidence stale = transactionTemplate.execute(
                status -> evidenceRepository.findById(evidenceId).orElseThrow());
        transactionTemplate.executeWithoutResult(status -> {
            DigitalEvidence current = evidenceRepository.findById(evidenceId).orElseThrow();
            current.updateMetadata("Current title", null);
            evidenceRepository.flush();
        });

        stale.updateMetadata("Stale title", null);
        assertThatThrownBy(() ->
                        transactionTemplate.executeWithoutResult(status -> evidenceRepository.saveAndFlush(stale)))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void deletingEvidenceDoesNotCascadeAndParentsCannotBeDeletedWhileReferenced() {
        EvidenceContext first = saveEvidence("no-cascade", "TAG-01");
        UUID caseId = first.custodyCase().getId();
        UUID holderId = first.holder().getId();
        UUID uploaderId = first.uploader().getId();

        evidenceRepository.deleteById(first.evidence().getId());
        evidenceRepository.flush();
        assertThat(custodyCaseRepository.existsById(caseId)).isTrue();
        assertThat(operatorRepository.existsById(holderId)).isTrue();
        assertThat(operatorRepository.existsById(uploaderId)).isTrue();

        EvidenceContext second = saveEvidence("restrict", "TAG-02");
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "DELETE FROM custody_cases WHERE id = ?",
                        second.custodyCase().getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "DELETE FROM operators WHERE id = ?", second.holder().getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(evidenceRepository.existsById(second.evidence().getId())).isTrue();
    }

    private void cleanDatabaseInDependencyOrder() {
        evidenceRepository.deleteAllInBatch();
        membershipRepository.deleteAllInBatch();
        custodyCaseRepository.deleteAllInBatch();
        operatorRepository.deleteAllInBatch();
    }

    private EvidenceContext saveEvidence(String suffix, String referenceTag) {
        Operator uploader = saveOperator(suffix + "-uploader", OperatorRole.ADMIN);
        Operator holder = saveOperator(suffix + "-holder", OperatorRole.CASE_MANAGER);
        CustodyCase custodyCase = custodyCaseRepository.saveAndFlush(custodyCase("Evidence case " + suffix, uploader));
        DigitalEvidence evidence = evidenceRepository.saveAndFlush(
                evidence(custodyCase, holder, uploader, referenceTag, "Evidence " + suffix));
        return new EvidenceContext(uploader, holder, custodyCase, evidence);
    }

    private Operator saveOperator(String username, OperatorRole role) {
        return operatorRepository.saveAndFlush(
                Operator.create(username, username + "@example.com", BCRYPT_HASH, "Jane", "Doe", role));
    }

    private static CustodyCase custodyCase(String title, Operator uploader) {
        return CustodyCase.create(title, null, null, null, null, CasePriority.MEDIUM, uploader);
    }

    private static DigitalEvidence evidence(
            CustodyCase custodyCase, Operator holder, Operator uploader, String referenceTag, String title) {
        return DigitalEvidence.create(
                custodyCase,
                holder,
                uploader,
                referenceTag,
                title,
                "Forensic disk image",
                SourceType.DEVICE,
                "Workstation",
                "Acme",
                "Model X",
                "SN-001",
                "disk0",
                AcquisitionMethod.PHYSICAL,
                "Evidence room",
                "Imager",
                "1.0",
                "Write blocker used",
                Instant.EPOCH,
                "disk-image.E01",
                null,
                4096L,
                CONTENT_SHA_256,
                CONTEXTUAL_SHA_256,
                STORAGE_KEY);
    }

    private void replaceIdentityAndTimestamp(UUID oldId, UUID newId, Instant timestamp) {
        jdbcTemplate.update(
                "UPDATE digital_evidence SET id = ?, created_at = ?, updated_at = ? WHERE id = ?",
                newId,
                Timestamp.from(timestamp),
                Timestamp.from(timestamp),
                oldId);
    }

    private void assertDatabaseRejects(String sql, Object... arguments) {
        assertThatThrownBy(() -> jdbcTemplate.update(sql, arguments))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Map<String, ColumnDefinition> columnsFor(String tableName) {
        return jdbcTemplate
                .query(
                        """
                        SELECT column_name, data_type, character_maximum_length, datetime_precision
                        FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = ?
                        """,
                        (resultSet, rowNumber) -> Map.entry(
                                resultSet.getString("column_name"),
                                new ColumnDefinition(
                                        resultSet.getString("data_type"),
                                        integerOrNull(resultSet, "character_maximum_length"),
                                        integerOrNull(resultSet, "datetime_precision"))),
                        tableName)
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Set<String> constraintsFor(String tableName) {
        return Set.copyOf(jdbcTemplate.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = ?::regclass", String.class, tableName));
    }

    private String indexDefinition(String indexName) {
        return jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = ?", String.class, indexName);
    }

    private static Integer integerOrNull(ResultSet resultSet, String columnName) throws SQLException {
        int value = resultSet.getInt(columnName);
        return resultSet.wasNull() ? null : value;
    }

    private record ColumnDefinition(String dataType, Integer maximumLength, Integer datetimePrecision) {}

    private record EvidenceContext(
            Operator uploader, Operator holder, CustodyCase custodyCase, DigitalEvidence evidence) {}
}
