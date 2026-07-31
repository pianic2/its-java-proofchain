package it.itsprodigi.proofchain.migration;

import it.itsprodigi.proofchain.ProofChainApplication;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Owns one disposable PostgreSQL schema inside the shared Testcontainers instance and exposes exactly the primitives a
 * schema-lifecycle certification needs: Flyway configured with the frozen production settings, a real Spring Boot
 * startup on that schema, the recorded migration history, and a normalized structural fingerprint of the result.
 *
 * <p>Every schema this harness creates is dropped by {@link #close()}. Dropping a private, test-owned schema is not a
 * database reset: no production path, startup script or application bean may ever drop, clean or repair anything, and
 * {@link MigrationGovernanceTest} proves none does.
 */
final class MigrationSchemaHarness implements AutoCloseable {

    /** The only migration location the delivered runtime ever reads. */
    static final String PRODUCTION_LOCATION = "classpath:db/migration";

    /** Test-only location holding a deliberately invalid migration; never on the production path. */
    static final String INVALID_MIGRATION_LOCATION = "classpath:db/invalid";

    private static final Pattern DISPOSABLE_SCHEMA = Pattern.compile("mig_[0-9a-f]{32}");

    private final String url;
    private final String username;
    private final String password;
    private final String schema;

    MigrationSchemaHarness(String url, String username, String password) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.schema = "mig_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE SCHEMA \"" + schema + "\"");
    }

    String schema() {
        return schema;
    }

    /** A connection whose search path is the disposable schema, so unqualified SQL hits the reconstructed baseline. */
    Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(url, username, password);
        connection.setSchema(schema);
        return connection;
    }

    /**
     * Flyway configured exactly like {@code application.yml}: no baseline shortcut, mandatory validation, no
     * out-of-order application and clean permanently disabled.
     */
    Flyway flyway(Integer target, String... locations) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(url, username, password)
                .defaultSchema(schema)
                .schemas(schema)
                .locations(locations.length == 0 ? new String[] {PRODUCTION_LOCATION} : locations)
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .outOfOrder(false)
                .cleanDisabled(true);
        if (target != null) {
            configuration.target(MigrationVersion.fromVersion(String.valueOf(target)));
        }
        return configuration.load();
    }

    void migrateTo(int version) {
        flyway(version).migrate();
    }

    void migrateToFinalVersion() {
        flyway(null).migrate();
    }

    /**
     * Starts the delivered application against this schema. Flyway runs the pending migrations, Hibernate then runs
     * {@code ddl-auto: validate}, and the context only becomes available when both succeed.
     */
    ConfigurableApplicationContext startApplication(String... extraArguments) {
        List<String> arguments = new ArrayList<>(List.of(
                "--spring.datasource.url=" + url,
                "--spring.datasource.username=" + username,
                "--spring.datasource.password=" + password,
                "--spring.flyway.schemas=" + schema,
                "--spring.flyway.default-schema=" + schema,
                "--spring.jpa.properties.hibernate.default_schema=" + schema));
        arguments.addAll(List.of(extraArguments));
        return new SpringApplicationBuilder(ProofChainApplication.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .profiles("test")
                .run(arguments.toArray(String[]::new));
    }

    List<HistoryRow> history() {
        List<HistoryRow> rows = new ArrayList<>();
        try (Connection connection = open();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT installed_rank, version, description, type, script, checksum, installed_on,
                               execution_time, success
                        FROM flyway_schema_history
                        ORDER BY installed_rank
                        """)) {
            while (result.next()) {
                Integer checksum = (Integer) result.getObject(6);
                rows.add(new HistoryRow(
                        result.getInt(1),
                        result.getString(2),
                        result.getString(3),
                        result.getString(4),
                        result.getString(5),
                        checksum,
                        result.getTimestamp(7),
                        result.getBoolean(9)));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to read the Flyway schema history", exception);
        }
        return List.copyOf(rows);
    }

    boolean hasSchemaHistoryTable() {
        return queryBoolean("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_catalog.pg_tables WHERE schemaname = ? AND tablename = 'flyway_schema_history'
                )
                """);
    }

    List<String> tableNames() {
        return queryStrings("SELECT tablename FROM pg_catalog.pg_tables WHERE schemaname = ? ORDER BY tablename");
    }

    /**
     * A normalized fingerprint of everything the migrations create: columns, constraints, indexes, triggers and
     * functions. The schema name is replaced by a placeholder so two schemas built by different routes compare equal.
     */
    List<String> structure() {
        List<String> structure = new ArrayList<>();
        structure.addAll(prefix("column", queryStrings("""
                        SELECT table_name || '.' || column_name || ' ' || data_type
                               || ' len=' || COALESCE(character_maximum_length::text, '-')
                               || ' nullable=' || is_nullable
                               || ' default=' || COALESCE(column_default, '-')
                        FROM information_schema.columns
                        WHERE table_schema = ?
                        ORDER BY 1
                        """)));
        structure.addAll(prefix("constraint", queryStrings("""
                        SELECT c.conrelid::regclass::text || '.' || c.conname || ' ' || pg_get_constraintdef(c.oid)
                        FROM pg_constraint c
                        JOIN pg_namespace n ON n.oid = c.connamespace
                        WHERE n.nspname = ?
                        ORDER BY 1
                        """)));
        structure.addAll(prefix(
                "index",
                queryStrings("SELECT indexname || ' ' || indexdef FROM pg_indexes WHERE schemaname = ? ORDER BY 1")));
        structure.addAll(prefix("trigger", queryStrings("""
                        SELECT c.relname || '.' || t.tgname || ' ' || pg_get_triggerdef(t.oid)
                        FROM pg_trigger t
                        JOIN pg_class c ON c.oid = t.tgrelid
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = ? AND NOT t.tgisinternal
                        ORDER BY 1
                        """)));
        structure.addAll(prefix("function", queryStrings("""
                        SELECT p.proname || ' ' || pg_get_functiondef(p.oid)
                        FROM pg_proc p
                        JOIN pg_namespace n ON n.oid = p.pronamespace
                        WHERE n.nspname = ?
                        ORDER BY 1
                        """)));
        return List.copyOf(structure);
    }

    void execute(String sql, Object... parameters) {
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            connection.setSchema(schema);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, parameters);
                statement.execute();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to execute " + sql, exception);
        }
    }

    /** Full rows as ordered column-name to value maps, so preservation can be asserted column by column. */
    List<Map<String, Object>> rows(String sql, Object... parameters) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                ResultSetMetaData metaData = result.getMetaData();
                while (result.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int column = 1; column <= metaData.getColumnCount(); column++) {
                        row.put(metaData.getColumnLabel(column), result.getObject(column));
                    }
                    rows.add(Collections.unmodifiableMap(row));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to read rows with " + sql, exception);
        }
        return List.copyOf(rows);
    }

    <T> T scalar(Class<T> type, String sql, Object... parameters) {
        try (Connection connection = open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getObject(1, type) : null;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to read a value with " + sql, exception);
        }
    }

    long count(String sql, Object... parameters) {
        try (Connection connection = open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to count with " + sql, exception);
        }
    }

    @Override
    public void close() {
        if (!DISPOSABLE_SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Refusing to drop a schema that is not a disposable test schema");
        }
        execute("DROP SCHEMA \"" + schema + "\" CASCADE");
    }

    private List<String> prefix(String kind, List<String> values) {
        return values.stream()
                .map(value -> kind + " " + value.replace(schema + ".", "").replace(schema, "SCHEMA"))
                .toList();
    }

    private List<String> queryStrings(String sql) {
        List<String> values = new ArrayList<>();
        try (Connection connection = open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    values.add(result.getString(1));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to introspect the schema", exception);
        }
        return List.copyOf(values);
    }

    private boolean queryBoolean(String sql) {
        try (Connection connection = open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to inspect the schema", exception);
        }
    }

    private static void bind(PreparedStatement statement, Object... parameters) throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
    }

    /** One row of {@code flyway_schema_history}, including the timestamp that proves a row was never rewritten. */
    record HistoryRow(
            int installedRank,
            String version,
            String description,
            String type,
            String script,
            Integer checksum,
            Timestamp installedOn,
            boolean success) {}
}
