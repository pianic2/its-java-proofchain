package it.itsprodigi.proofchain.custodycase.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.itsprodigi.proofchain.custodycase.domain.CaseMembership;
import it.itsprodigi.proofchain.custodycase.domain.CasePriority;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceUnitUtil;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class CustodyCaseRepositoryIT extends PostgreSqlIntegrationTest {

    private static final String BCRYPT_HASH = "$2a$10$01234567890123456789012345678901234567890123456789012";

    @Autowired
    private CaseMembershipRepository caseMembershipRepository;

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

    private void cleanDatabaseInDependencyOrder() {
        caseMembershipRepository.deleteAllInBatch();
        custodyCaseRepository.deleteAllInBatch();
        operatorRepository.deleteAllInBatch();
    }

    @Test
    void flywayCreatesTheCaseAndMembershipSchemaThatHibernateValidates() {
        assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class))
                .isTrue();
        assertThat(environment.getProperty("spring.flyway.baseline-on-migrate", Boolean.class))
                .isFalse();
        assertThat(environment.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration");
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");

        Map<String, ColumnDefinition> caseColumns = columnsFor("custody_cases");
        Map<String, ColumnDefinition> membershipColumns = columnsFor("case_memberships");

        assertThat(caseColumns)
                .containsEntry("id", new ColumnDefinition("uuid", null, null))
                .containsEntry("title", new ColumnDefinition("character varying", 200, null))
                .containsEntry("description", new ColumnDefinition("character varying", 2000, null))
                .containsEntry("created_at", new ColumnDefinition("timestamp with time zone", null, 6))
                .containsEntry("updated_at", new ColumnDefinition("timestamp with time zone", null, 6))
                .containsEntry("closed_at", new ColumnDefinition("timestamp with time zone", null, 6))
                .containsEntry("version", new ColumnDefinition("bigint", null, null));
        assertThat(caseColumns.keySet())
                .containsExactlyInAnyOrder(
                        "id",
                        "title",
                        "description",
                        "authority_name",
                        "external_reference",
                        "location",
                        "priority",
                        "status",
                        "created_by_operator_id",
                        "created_at",
                        "updated_at",
                        "closed_at",
                        "version");
        assertThat(membershipColumns)
                .containsEntry("id", new ColumnDefinition("uuid", null, null))
                .containsEntry("case_id", new ColumnDefinition("uuid", null, null))
                .containsEntry("operator_id", new ColumnDefinition("uuid", null, null))
                .containsEntry("assigned_by_operator_id", new ColumnDefinition("uuid", null, null))
                .containsEntry("assigned_at", new ColumnDefinition("timestamp with time zone", null, 6));
        assertThat(membershipColumns.keySet())
                .containsExactlyInAnyOrder("id", "case_id", "operator_id", "assigned_by_operator_id", "assigned_at");

        Set<String> caseConstraints = constraintsFor("custody_cases");
        Set<String> membershipConstraints = constraintsFor("case_memberships");
        assertThat(caseConstraints)
                .contains(
                        "pk_custody_cases",
                        "fk_custody_cases_created_by_operator",
                        "ck_custody_cases_title",
                        "ck_custody_cases_priority",
                        "ck_custody_cases_status",
                        "ck_custody_cases_closed_at_by_status",
                        "ck_custody_cases_version_non_negative");
        assertThat(membershipConstraints)
                .contains(
                        "pk_case_memberships",
                        "fk_case_memberships_case",
                        "fk_case_memberships_operator",
                        "fk_case_memberships_assigned_by_operator",
                        "uk_case_memberships_case_operator");
        assertThat(indexDefinition("ix_custody_cases_created_at_id"))
                .contains("created_at DESC")
                .contains("id");
        assertThat(indexDefinition("ix_case_memberships_case_assigned_at_id"))
                .contains("case_id")
                .contains("assigned_at")
                .contains("id");
        assertThat(indexDefinition("ix_case_memberships_operator_case"))
                .contains("operator_id")
                .contains("case_id");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT success FROM flyway_schema_history WHERE version = '2'", Boolean.class))
                .isTrue();
    }

    @Test
    void persistsUuidV4MicrosecondInstantsAndLazyRelationsWhileSupportingFetchPlans() {
        Operator owner = saveOperator("owner", OperatorRole.ADMIN);
        Operator member = saveOperator("member", OperatorRole.CASE_MANAGER);
        CustodyCase savedCase = custodyCaseRepository.saveAndFlush(custodyCase("  Case title  ", owner));
        CaseMembership savedMembership =
                caseMembershipRepository.saveAndFlush(CaseMembership.assign(savedCase, member, owner));
        UUID caseId = savedCase.getId();
        UUID membershipId = savedMembership.getId();
        PersistenceUnitUtil persistenceUnitUtil =
                entityManager.getEntityManagerFactory().getPersistenceUnitUtil();

        entityManager.clear();
        CustodyCase regularLookup = custodyCaseRepository.findById(caseId).orElseThrow();
        CaseMembership regularMembershipLookup =
                caseMembershipRepository.findById(membershipId).orElseThrow();

        assertThat(regularLookup.getId().version()).isEqualTo(4);
        assertThat(regularLookup.getCreatedAt())
                .isEqualTo(regularLookup.getCreatedAt().truncatedTo(ChronoUnit.MICROS));
        assertThat(regularLookup.getUpdatedAt())
                .isEqualTo(regularLookup.getUpdatedAt().truncatedTo(ChronoUnit.MICROS));
        assertThat(regularLookup.getVersion()).isZero();
        assertThat(regularMembershipLookup.getId().version()).isEqualTo(4);
        assertThat(regularMembershipLookup.getAssignedAt())
                .isEqualTo(regularMembershipLookup.getAssignedAt().truncatedTo(ChronoUnit.MICROS));
        assertThat(persistenceUnitUtil.isLoaded(regularLookup, "createdBy")).isFalse();
        assertThat(persistenceUnitUtil.isLoaded(regularMembershipLookup, "custodyCase"))
                .isFalse();
        assertThat(persistenceUnitUtil.isLoaded(regularMembershipLookup, "operator"))
                .isFalse();
        assertThat(persistenceUnitUtil.isLoaded(regularMembershipLookup, "assignedBy"))
                .isFalse();

        entityManager.clear();
        CustodyCase caseWithCreator =
                custodyCaseRepository.findByIdWithCreatedBy(caseId).orElseThrow();
        CaseMembership membershipWithOperators = caseMembershipRepository
                .findByCaseIdAndOperatorId(caseId, member.getId())
                .orElseThrow();

        assertThat(persistenceUnitUtil.isLoaded(caseWithCreator, "createdBy")).isTrue();
        assertThat(persistenceUnitUtil.isLoaded(membershipWithOperators, "operator"))
                .isTrue();
        assertThat(persistenceUnitUtil.isLoaded(membershipWithOperators, "assignedBy"))
                .isTrue();
    }

    @Test
    void databaseEnforcesCaseConstraintsForeignKeysAndMicrosecondPrecision() {
        Operator owner = saveOperator("owner", OperatorRole.ADMIN);
        Instant submittedTimestamp = Instant.parse("2026-07-29T12:34:56.123456Z");
        RawCase validCase = rawCase(UUID.randomUUID(), owner.getId(), submittedTimestamp, "Valid custody case");
        insertRawCase(validCase);

        Instant persistedTimestamp = jdbcTemplate.queryForObject(
                "SELECT created_at FROM custody_cases WHERE id = ?",
                (resultSet, rowNumber) -> resultSet.getTimestamp(1).toInstant(),
                validCase.id);
        assertThat(persistedTimestamp).isEqualTo(submittedTimestamp);

        assertDatabaseRejects(owner.getId(), values -> values.title = "  invalid  ");
        assertDatabaseRejects(owner.getId(), values -> values.description = " ");
        assertDatabaseRejects(owner.getId(), values -> values.priority = "URGENT");
        assertDatabaseRejects(owner.getId(), values -> values.status = "ARCHIVED");
        assertDatabaseRejects(owner.getId(), values -> values.closedAt = Instant.now());
        assertDatabaseRejects(owner.getId(), values -> {
            values.status = "CLOSED";
            values.closedAt = null;
        });
        assertDatabaseRejects(owner.getId(), values -> values.version = -1L);
        assertThatThrownBy(() -> insertRawCase(rawCase(UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseEnforcesUniqueMembershipAndRepositoryQueriesUseTheFrozenOrder() {
        Operator owner = saveOperator("owner", OperatorRole.ADMIN);
        Operator memberOne = saveOperator("member-one", OperatorRole.CASE_MANAGER);
        Operator memberTwo = saveOperator("member-two", OperatorRole.AUDITOR);
        Instant older = Instant.parse("2026-07-29T08:00:00Z");
        Instant newer = Instant.parse("2026-07-29T09:00:00Z");
        UUID sameTimeLowId = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID sameTimeHighId = UUID.fromString("00000000-0000-4000-8000-000000000002");
        UUID newestId = UUID.fromString("00000000-0000-4000-8000-000000000003");

        insertRawCase(rawCase(sameTimeHighId, owner.getId(), older, "Same time high id"));
        insertRawCase(rawCase(sameTimeLowId, owner.getId(), older, "Same time low id"));
        insertRawCase(rawCase(newestId, owner.getId(), newer, "Newest case"));

        Instant assignedAt = Instant.parse("2026-07-29T10:00:00Z");
        UUID firstMembershipId = UUID.fromString("00000000-0000-4000-8000-000000000010");
        UUID secondMembershipId = UUID.fromString("00000000-0000-4000-8000-000000000011");
        insertRawMembership(firstMembershipId, newestId, memberOne.getId(), owner.getId(), assignedAt);
        insertRawMembership(secondMembershipId, newestId, memberTwo.getId(), owner.getId(), assignedAt);
        insertRawMembership(UUID.randomUUID(), sameTimeHighId, memberOne.getId(), owner.getId(), assignedAt);

        assertThatThrownBy(() -> insertRawMembership(
                        UUID.randomUUID(),
                        newestId,
                        memberOne.getId(),
                        owner.getId(),
                        assignedAt.plus(1, ChronoUnit.SECONDS)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(caseMembershipRepository.existsByCustodyCaseIdAndOperatorId(newestId, memberOne.getId()))
                .isTrue();

        entityManager.clear();
        List<CustodyCase> adminPage =
                custodyCaseRepository.findPageForAdmin(PageRequest.of(0, 3)).getContent();
        List<CustodyCase> memberPage = custodyCaseRepository
                .findPageForMember(memberOne.getId(), PageRequest.of(0, 20))
                .getContent();
        List<CaseMembership> memberList =
                caseMembershipRepository.findAllByCustodyCaseIdOrderByAssignedAtAscIdAsc(newestId);
        PersistenceUnitUtil persistenceUnitUtil =
                entityManager.getEntityManagerFactory().getPersistenceUnitUtil();

        assertThat(adminPage).extracting(CustodyCase::getId).containsExactly(newestId, sameTimeLowId, sameTimeHighId);
        assertThat(memberPage).extracting(CustodyCase::getId).containsExactly(newestId, sameTimeHighId);
        assertThat(memberList).extracting(CaseMembership::getId).containsExactly(firstMembershipId, secondMembershipId);
        assertThat(adminPage)
                .allSatisfy(custodyCase -> assertThat(persistenceUnitUtil.isLoaded(custodyCase, "createdBy"))
                        .isTrue());
        assertThat(memberPage)
                .allSatisfy(custodyCase -> assertThat(persistenceUnitUtil.isLoaded(custodyCase, "createdBy"))
                        .isTrue());
        assertThat(memberList).allSatisfy(membership -> {
            assertThat(persistenceUnitUtil.isLoaded(membership, "operator")).isTrue();
            assertThat(persistenceUnitUtil.isLoaded(membership, "assignedBy")).isTrue();
        });
    }

    @Test
    void rejectsAStaleUpdateToTheSameCustodyCase() {
        Operator owner = saveOperator("owner", OperatorRole.ADMIN);
        UUID caseId = custodyCaseRepository
                .saveAndFlush(custodyCase("Initial case", owner))
                .getId();

        CustodyCase stale = transactionTemplate.execute(
                status -> custodyCaseRepository.findById(caseId).orElseThrow());
        transactionTemplate.executeWithoutResult(status -> {
            CustodyCase current = custodyCaseRepository.findById(caseId).orElseThrow();
            current.updateMetadata("Current case", null, null, null, null, CasePriority.HIGH);
            custodyCaseRepository.flush();
        });

        stale.updateMetadata("Stale case", null, null, null, null, CasePriority.LOW);
        assertThatThrownBy(() ->
                        transactionTemplate.executeWithoutResult(status -> custodyCaseRepository.saveAndFlush(stale)))
                .isInstanceOf(OptimisticLockingFailureException.class);
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

    private void assertDatabaseRejects(UUID ownerId, Consumer<RawCase> mutation) {
        RawCase values = rawCase(ownerId);
        mutation.accept(values);

        assertThatThrownBy(() -> insertRawCase(values)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private Operator saveOperator(String username, OperatorRole role) {
        return operatorRepository.saveAndFlush(
                Operator.create(username, username + "@example.com", BCRYPT_HASH, "Jane", "Doe", role));
    }

    private static CustodyCase custodyCase(String title, Operator owner) {
        return CustodyCase.create(title, null, null, null, null, CasePriority.MEDIUM, owner);
    }

    private void insertRawCase(RawCase values) {
        jdbcTemplate.update(
                """
                INSERT INTO custody_cases (
                    id, title, description, authority_name, external_reference, location,
                    priority, status, created_by_operator_id, created_at, updated_at, closed_at, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                values.id,
                values.title,
                values.description,
                values.authorityName,
                values.externalReference,
                values.location,
                values.priority,
                values.status,
                values.createdByOperatorId,
                Timestamp.from(values.createdAt),
                Timestamp.from(values.updatedAt),
                values.closedAt == null ? null : Timestamp.from(values.closedAt),
                values.version);
    }

    private void insertRawMembership(
            UUID id, UUID caseId, UUID operatorId, UUID assignedByOperatorId, Instant assignedAt) {
        jdbcTemplate.update("""
                INSERT INTO case_memberships (id, case_id, operator_id, assigned_by_operator_id, assigned_at)
                VALUES (?, ?, ?, ?, ?)
                """, id, caseId, operatorId, assignedByOperatorId, Timestamp.from(assignedAt));
    }

    private static RawCase rawCase(UUID ownerId) {
        return rawCase(UUID.randomUUID(), ownerId, Instant.now().truncatedTo(ChronoUnit.MICROS), "Valid custody case");
    }

    private static RawCase rawCase(UUID id, UUID ownerId, Instant createdAt, String title) {
        return new RawCase(id, ownerId, createdAt, title);
    }

    private static Integer integerOrNull(java.sql.ResultSet resultSet, String columnName) throws java.sql.SQLException {
        int value = resultSet.getInt(columnName);
        return resultSet.wasNull() ? null : value;
    }

    private record ColumnDefinition(String dataType, Integer maximumLength, Integer datetimePrecision) {}

    private static final class RawCase {
        private final UUID id;
        private final UUID createdByOperatorId;
        private final Instant createdAt;
        private String title;
        private String description;
        private String authorityName;
        private String externalReference;
        private String location;
        private String priority = "MEDIUM";
        private String status = "OPEN";
        private Instant updatedAt;
        private Instant closedAt;
        private long version;

        private RawCase(UUID id, UUID createdByOperatorId, Instant createdAt, String title) {
            this.id = id;
            this.createdByOperatorId = createdByOperatorId;
            this.createdAt = createdAt;
            this.title = title;
            updatedAt = createdAt;
        }
    }
}
