package it.itsprodigi.proofchain.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
public abstract class PostgreSqlIntegrationTest {

    /**
     * The integration suite keeps one Spring context alive per distinct test configuration, and every context owns its
     * own connection pool, so the default backend limit is raised to leave headroom as the suite grows. This is a
     * capacity setting only: no isolation, durability or locking behavior is relaxed.
     */
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-trixie")
            .withDatabaseName("proofchain_test")
            .withUsername("proofchain_test")
            .withPassword("proofchain_test")
            .withCommand("postgres", "-c", "max_connections=400");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
