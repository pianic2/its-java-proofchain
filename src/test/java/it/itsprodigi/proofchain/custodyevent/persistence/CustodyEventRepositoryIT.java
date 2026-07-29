package it.itsprodigi.proofchain.custodyevent.persistence;

import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.EVENT_HASH;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.ZERO_HASH;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.custodyCase;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.event;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.evidence;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.operator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.custodycase.persistence.CustodyCaseRepository;
import it.itsprodigi.proofchain.custodyevent.domain.CustodyEvent;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceRepository;
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
import java.util.Arrays;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

class CustodyEventRepositoryIT extends PostgreSqlIntegrationTest {

    @Autowired
    private CustodyEventRepository events;

    @Autowired
    private DigitalEvidenceRepository evidences;

    @Autowired
    private CaseMembershipRepository memberships;

    @Autowired
    private CustodyCaseRepository custodyCases;

    @Autowired
    private OperatorRepository operators;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

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
    void flywayCreatesJsonbCompositeIntegrityNoCascadeIndexesAndAppendOnlyTrigger() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT success FROM flyway_schema_history WHERE version = '4'", Boolean.class))
                .isTrue();

        Map<String, ColumnDefinition> columns = columnsFor("custody_events");
        assertThat(columns)
                .containsEntry("id", new ColumnDefinition("uuid", null, null))
                .containsEntry("case_id", new ColumnDefinition("uuid", null, null))
                .containsEntry("evidence_id", new ColumnDefinition("uuid", null, null))
                .containsEntry("operator_id", new ColumnDefinition("uuid", null, null))
                .containsEntry("actor_role", new ColumnDefinition("character varying", 32, null))
                .containsEntry("sequence_number", new ColumnDefinition("bigint", null, null))
                .containsEntry("event_type", new ColumnDefinition("character varying", 32, null))
                .containsEntry("occurred_at", new ColumnDefinition("timestamp with time zone", null, 6))
                .containsEntry("payload_json", new ColumnDefinition("jsonb", null, null))
                .containsEntry("previous_hash", new ColumnDefinition("character varying", 64, null))
                .containsEntry("event_hash", new ColumnDefinition("character varying", 64, null));
        assertThat(columns.keySet())
                .containsExactlyInAnyOrder(
                        "id",
                        "case_id",
                        "evidence_id",
                        "operator_id",
                        "actor_role",
                        "sequence_number",
                        "event_type",
                        "occurred_at",
                        "payload_version",
                        "payload_json",
                        "previous_hash",
                        "event_hash",
                        "hash_version");

        assertThat(constraintsFor("custody_events"))
                .contains(
                        "pk_custody_events",
                        "fk_custody_events_case",
                        "fk_custody_events_evidence_case",
                        "fk_custody_events_operator",
                        "uk_custody_events_evidence_sequence",
                        "uk_custody_events_evidence_hash",
                        "ck_custody_events_sequence_positive",
                        "ck_custody_events_event_type",
                        "ck_custody_events_payload_version",
                        "ck_custody_events_previous_hash",
                        "ck_custody_events_event_hash",
                        "ck_custody_events_hash_version");
        assertThat(constraintsFor("digital_evidence")).contains("uk_digital_evidence_id_case");

        Map<String, String> deleteActions = jdbcTemplate
                .query("""
                        SELECT conname, confdeltype::text
                        FROM pg_constraint
                        WHERE conrelid = 'custody_events'::regclass AND contype = 'f'
                        """, (resultSet, rowNumber) -> Map.entry(resultSet.getString(1), resultSet.getString(2)))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(deleteActions)
                .containsOnly(
                        Map.entry("fk_custody_events_case", "a"),
                        Map.entry("fk_custody_events_evidence_case", "a"),
                        Map.entry("fk_custody_events_operator", "a"));
        assertThat(indexDefinition("uk_custody_events_evidence_sequence")).contains("(evidence_id, sequence_number)");
        assertThat(indexDefinition("ix_custody_events_case_id")).contains("(case_id)");
        assertThat(indexDefinition("ix_custody_events_operator_id")).contains("(operator_id)");
        assertThat(indexDefinition("ix_custody_events_event_type")).contains("(event_type)");
        assertThat(indexDefinition("ix_custody_events_occurred_at")).contains("(occurred_at)");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT tgenabled = 'O' FROM pg_trigger WHERE tgname = 'custody_events_append_only'",
                        Boolean.class))
                .isTrue();

        assertThat(JpaRepository.class.isAssignableFrom(CustodyEventRepository.class))
                .isFalse();
        assertThat(Arrays.stream(CustodyEventRepository.class.getMethods()).map(method -> method.getName()))
                .noneMatch(name -> name.startsWith("delete"));
    }

    @Test
    void persistsJsonbAndReadsAnEvidenceScopedTimelineDeterministically() {
        EventContext context = saveContext("ordered");
        CustodyEvent second =
                events.save(event(context.custodyCase(), context.evidence(), context.actor(), 2, "2".repeat(64)));
        CustodyEvent first =
                events.save(event(context.custodyCase(), context.evidence(), context.actor(), 1, EVENT_HASH));
        entityManager.clear();

        java.util.List<CustodyEvent> ordered = events.findAllByEvidenceIdOrderBySequenceNumberAsc(
                context.evidence().getId());
        Page<CustodyEvent> page = events.findAllByEvidenceIdOrderBySequenceNumberAsc(
                context.evidence().getId(), PageRequest.of(0, 1));

        assertThat(ordered).extracting(CustodyEvent::getId).containsExactly(first.getId(), second.getId());
        assertThat(ordered.getFirst().getPayloadJson()).contains("\"action\"").contains("\"registered\"");
        assertThat(ordered.getFirst().getOccurredAt()).isEqualTo(Instant.parse("2026-07-29T12:34:56.123456Z"));
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(CustodyEvent::getId).containsExactly(first.getId());
        assertThat(events.countByEvidenceId(context.evidence().getId())).isEqualTo(2);
        assertThat(events.findFirstByEvidenceIdOrderBySequenceNumberAsc(
                        context.evidence().getId()))
                .get()
                .extracting(CustodyEvent::getId)
                .isEqualTo(first.getId());
        assertThat(events.findFirstByEvidenceIdOrderBySequenceNumberDesc(
                        context.evidence().getId()))
                .get()
                .extracting(CustodyEvent::getId)
                .isEqualTo(second.getId());
        assertThat(events.findByEvidenceIdAndEventId(context.evidence().getId(), first.getId()))
                .get()
                .extracting(CustodyEvent::getId)
                .isEqualTo(first.getId());

        PersistenceUnitUtil persistence =
                entityManager.getEntityManagerFactory().getPersistenceUnitUtil();
        assertThat(persistence.isLoaded(ordered.getFirst(), "custodyCase")).isTrue();
        assertThat(persistence.isLoaded(ordered.getFirst(), "evidence")).isTrue();
        assertThat(persistence.isLoaded(ordered.getFirst(), "operator")).isTrue();
    }

    @Test
    void databaseRejectsFrozenValueViolationsDuplicatesAndCaseEvidenceMismatch() {
        EventContext context = saveContext("constraints");
        CustodyEvent persisted =
                events.save(event(context.custodyCase(), context.evidence(), context.actor(), 1, EVENT_HASH));

        assertRawInsertRejected(
                context,
                0,
                "EVIDENCE_REGISTERED",
                1,
                "{}",
                ZERO_HASH,
                "1".repeat(64),
                1,
                context.custodyCase().getId(),
                context.evidence().getId());
        assertRawInsertRejected(
                context,
                2,
                "CUSTOM",
                1,
                "{}",
                ZERO_HASH,
                "2".repeat(64),
                1,
                context.custodyCase().getId(),
                context.evidence().getId());
        assertRawInsertRejected(
                context,
                2,
                "EVIDENCE_REGISTERED",
                2,
                "{}",
                ZERO_HASH,
                "3".repeat(64),
                1,
                context.custodyCase().getId(),
                context.evidence().getId());
        assertRawInsertRejected(
                context,
                2,
                "EVIDENCE_REGISTERED",
                1,
                "[]",
                ZERO_HASH,
                "4".repeat(64),
                1,
                context.custodyCase().getId(),
                context.evidence().getId());
        assertRawInsertRejected(
                context,
                2,
                "EVIDENCE_REGISTERED",
                1,
                "{}",
                "A".repeat(64),
                "5".repeat(64),
                1,
                context.custodyCase().getId(),
                context.evidence().getId());
        assertRawInsertRejected(
                context,
                2,
                "EVIDENCE_REGISTERED",
                1,
                "{}",
                ZERO_HASH,
                "short",
                1,
                context.custodyCase().getId(),
                context.evidence().getId());
        assertRawInsertRejected(
                context,
                2,
                "EVIDENCE_REGISTERED",
                1,
                "{}",
                ZERO_HASH,
                "6".repeat(64),
                2,
                context.custodyCase().getId(),
                context.evidence().getId());
        assertRawInsertRejected(
                context,
                1,
                "EVIDENCE_REGISTERED",
                1,
                "{}",
                ZERO_HASH,
                "7".repeat(64),
                1,
                context.custodyCase().getId(),
                context.evidence().getId());
        assertRawInsertRejected(
                context,
                2,
                "EVIDENCE_REGISTERED",
                1,
                "{}",
                ZERO_HASH,
                EVENT_HASH,
                1,
                context.custodyCase().getId(),
                context.evidence().getId());

        CustodyCase otherCase = custodyCases.saveAndFlush(custodyCase("Other relational case", context.actor()));
        assertRawInsertRejected(
                context,
                2,
                "EVIDENCE_REGISTERED",
                1,
                "{}",
                ZERO_HASH,
                "8".repeat(64),
                1,
                otherCase.getId(),
                context.evidence().getId());
        assertRawInsertRejected(
                context,
                2,
                "EVIDENCE_REGISTERED",
                1,
                "{}",
                ZERO_HASH,
                "9".repeat(64),
                1,
                context.custodyCase().getId(),
                null);

        assertThat(events.countByEvidenceId(context.evidence().getId())).isEqualTo(1);
        assertThat(persisted.getId()).isNotNull();
    }

    @Test
    void appendOnlyTriggerRejectsMutationAndForeignKeysPreventParentDeletion() {
        EventContext context = saveContext("append-only");
        CustodyEvent persisted =
                events.save(event(context.custodyCase(), context.evidence(), context.actor(), 1, EVENT_HASH));
        entityManager.clear();

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE custody_events SET event_type = 'METADATA_UPDATED' WHERE id = ?", persisted.getId()))
                .isInstanceOf(UncategorizedSQLException.class)
                .rootCause()
                .hasMessageContaining("custody_events are append-only");
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM custody_events WHERE id = ?", persisted.getId()))
                .isInstanceOf(UncategorizedSQLException.class)
                .rootCause()
                .hasMessageContaining("custody_events are append-only");
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "DELETE FROM digital_evidence WHERE id = ?",
                        context.evidence().getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "DELETE FROM custody_cases WHERE id = ?",
                        context.custodyCase().getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "DELETE FROM operators WHERE id = ?", context.actor().getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(events.countByEvidenceId(context.evidence().getId())).isEqualTo(1);
    }

    private EventContext saveContext(String suffix) {
        Operator actor = operators.saveAndFlush(operator("event-" + suffix, OperatorRole.ADMIN));
        CustodyCase custodyCase = custodyCases.saveAndFlush(custodyCase("Custody event " + suffix, actor));
        DigitalEvidence evidence = evidences.saveAndFlush(evidence(custodyCase, actor, "EVENT-" + suffix));
        return new EventContext(actor, custodyCase, evidence);
    }

    private void assertRawInsertRejected(
            EventContext context,
            long sequence,
            String eventType,
            int payloadVersion,
            String payloadJson,
            String previousHash,
            String eventHash,
            int hashVersion,
            UUID caseId,
            UUID evidenceId) {
        assertThatThrownBy(() -> jdbcTemplate.update(
                        """
                        INSERT INTO custody_events (
                            id, case_id, evidence_id, operator_id, actor_role, sequence_number, event_type,
                            occurred_at, payload_version, payload_json, previous_hash, event_hash, hash_version
                        ) VALUES (?, ?, ?, ?, 'ADMIN', ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
                        """,
                        UUID.randomUUID(),
                        caseId,
                        evidenceId,
                        context.actor().getId(),
                        sequence,
                        eventType,
                        Timestamp.from(Instant.parse("2026-07-29T12:34:56.123456Z")),
                        payloadVersion,
                        payloadJson,
                        previousHash,
                        eventHash,
                        hashVersion))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void cleanDatabaseInDependencyOrder() {
        jdbcTemplate.execute("TRUNCATE TABLE custody_events");
        evidences.deleteAllInBatch();
        memberships.deleteAllInBatch();
        custodyCases.deleteAllInBatch();
        operators.deleteAllInBatch();
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

    private record EventContext(Operator actor, CustodyCase custodyCase, DigitalEvidence evidence) {}
}
