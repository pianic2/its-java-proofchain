package it.itsprodigi.proofchain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
class DatabaseBootstrapIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-trixie")
                    .withDatabaseName("proofchain_test")
                    .withUsername("proofchain_test")
                    .withPassword("proofchain_test");

    @Autowired private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void flywayBootstrapsSchemaHistoryOnAnEmptyPostgreSqlDatabase() {
        Boolean schemaHistoryExists =
                jdbcTemplate.queryForObject(
                        """
                        SELECT EXISTS (
                            SELECT 1
                            FROM pg_catalog.pg_tables
                            WHERE schemaname = 'public'
                              AND tablename = 'flyway_schema_history'
                        )
                        """,
                        Boolean.class);

        assertThat(schemaHistoryExists).isTrue();
    }
}
