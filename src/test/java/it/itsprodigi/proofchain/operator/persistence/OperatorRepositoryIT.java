package it.itsprodigi.proofchain.operator.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class OperatorRepositoryIT extends PostgreSqlIntegrationTest {

    private static final String BCRYPT_HASH = "$2a$10$01234567890123456789012345678901234567890123456789012";

    @Autowired
    private OperatorRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void cleanOperators() {
        repository.deleteAll();
    }

    @Test
    void flywayCreatesSchemaThatHibernateValidates() {
        Map<String, String> columnTypes = jdbcTemplate
                .query(
                        """
                        SELECT column_name, data_type
                        FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = 'operators'
                        """,
                        (resultSet, rowNumber) ->
                                Map.entry(resultSet.getString("column_name"), resultSet.getString("data_type")))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(columnTypes)
                .containsEntry("id", "uuid")
                .containsEntry("version", "bigint")
                .containsEntry("created_at", "timestamp with time zone")
                .containsEntry("updated_at", "timestamp with time zone");
        assertThat(columnTypes.keySet())
                .containsExactlyInAnyOrder(
                        "id",
                        "username",
                        "email",
                        "password_hash",
                        "first_name",
                        "last_name",
                        "role",
                        "status",
                        "created_at",
                        "updated_at",
                        "version");
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'operators'
                          AND is_nullable = 'NO'
                        """, Long.class)).isEqualTo(11L);
        Map<String, Integer> columnLengths = jdbcTemplate
                .query(
                        """
                        SELECT column_name, character_maximum_length
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'operators'
                          AND character_maximum_length IS NOT NULL
                        """,
                        (resultSet, rowNumber) -> Map.entry(
                                resultSet.getString("column_name"), resultSet.getInt("character_maximum_length")))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(columnLengths)
                .containsEntry("username", 64)
                .containsEntry("email", 320)
                .containsEntry("password_hash", 60)
                .containsEntry("first_name", 100)
                .containsEntry("last_name", 100)
                .containsEntry("role", 32)
                .containsEntry("status", 16);
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT column_default
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'operators'
                          AND column_name = 'version'
                        """, String.class)).contains("0");

        Set<String> constraints = Set.copyOf(jdbcTemplate.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'operators'::regclass", String.class));
        assertThat(constraints)
                .contains(
                        "pk_operators",
                        "uk_operators_username",
                        "uk_operators_email",
                        "ck_operators_username_normalized",
                        "ck_operators_email_normalized",
                        "ck_operators_role",
                        "ck_operators_status",
                        "ck_operators_version_non_negative");

        String indexDefinition = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'ix_operators_active_admin_id'", String.class);
        assertThat(indexDefinition).contains("(id)").contains("role").contains("status");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT success FROM flyway_schema_history WHERE version = '1'", Boolean.class))
                .isTrue();
    }

    @Test
    void persistsAndFindsNormalizedIdentityAndUtcInstants() {
        Operator saved = repository.saveAndFlush(operator("  Admin.One  ", "  Admin.One@Example.COM  "));
        UUID id = saved.getId();
        Instant createdAt = saved.getCreatedAt();

        repository.flush();
        entityManager.clear();
        Operator reloaded = repository.findById(id).orElseThrow();

        assertThat(repository.findByUsername("admin.one")).contains(reloaded);
        assertThat(repository.existsByUsername("admin.one")).isTrue();
        assertThat(repository.existsByEmail("admin.one@example.com")).isTrue();
        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.countActiveAdmins()).isEqualTo(1);
        assertThat(reloaded.getCreatedAt()).isCloseTo(createdAt, within(1, ChronoUnit.MICROS));
        assertThat(reloaded.getCreatedAt()).isEqualTo(reloaded.getUpdatedAt());
    }

    @Test
    void databaseEnforcesUniqueUsernameAndEmail() {
        insertRaw("unique", "unique@example.com", "ADMIN", "ACTIVE", 0);

        assertDatabaseRejects(values -> values.username = "unique");
        assertDatabaseRejects(values -> values.email = "unique@example.com");
    }

    @Test
    void databaseRejectsNonNormalizedIdentityValues() {
        assertDatabaseRejects(values -> values.username = "MixedCase");
        assertDatabaseRejects(values -> values.email = " Mixed@example.com ");
    }

    @Test
    void databaseRejectsUnsupportedEnumsAndNegativeVersion() {
        assertDatabaseRejects(values -> values.role = "SUPER_ADMIN");
        assertDatabaseRejects(values -> values.status = "PENDING");
        assertDatabaseRejects(values -> values.version = -1);
    }

    private void assertDatabaseRejects(Consumer<RawValues> mutation) {
        RawValues values = new RawValues();
        mutation.accept(values);

        assertThatThrownBy(() -> insertRaw(values.username, values.email, values.role, values.status, values.version))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertRaw(String username, String email, String role, String status, long version) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                """
                INSERT INTO operators (
                    id, username, email, password_hash, first_name, last_name,
                    role, status, created_at, updated_at, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                username,
                email,
                BCRYPT_HASH,
                "Jane",
                "Doe",
                role,
                status,
                Timestamp.from(now),
                Timestamp.from(now),
                version);
    }

    private static Operator operator(String username, String email) {
        return Operator.create(username, email, BCRYPT_HASH, "Jane", "Doe", OperatorRole.ADMIN);
    }

    private static final class RawValues {
        private String username = "valid-" + UUID.randomUUID();
        private String email = UUID.randomUUID() + "@example.com";
        private String role = "ADMIN";
        private String status = "ACTIVE";
        private long version;
    }
}
