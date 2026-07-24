package it.itsprodigi.proofchain;

import static org.assertj.core.api.Assertions.assertThat;

import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class DatabaseBootstrapIT extends PostgreSqlIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayBootstrapsSchemaHistoryOnAnEmptyPostgreSqlDatabase() {
        Boolean schemaHistoryExists = jdbcTemplate.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM pg_catalog.pg_tables
                            WHERE schemaname = 'public'
                              AND tablename = 'flyway_schema_history'
                        )
                        """, Boolean.class);

        assertThat(schemaHistoryExists).isTrue();
    }
}
