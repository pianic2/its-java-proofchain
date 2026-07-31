package it.itsprodigi.proofchain.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Empty-database certification.
 *
 * <p>A PostgreSQL instance that has never seen ProofChain must reach the final schema through the versioned Flyway
 * migrations alone, then satisfy Hibernate's {@code ddl-auto: validate} startup gate, become ready and serve the API.
 * The versioned migrations are the official SQL creation scripts: nothing else creates a table, and no dump is
 * replayed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmptyDatabaseCertificationIT extends PostgreSqlIntegrationTest {

    private static final String PASSWORD = "Certif1cation!Pass";

    private static final List<String> EXPECTED_TABLES = List.of(
            "case_memberships",
            "custody_cases",
            "custody_events",
            "digital_evidence",
            "flyway_schema_history",
            "operators");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JsonMapper jsonMapper;

    private UUID smokeOperatorId;
    private UUID smokeCaseId;

    @AfterEach
    void removeSmokeRows() {
        if (smokeCaseId != null) {
            jdbc.update("DELETE FROM case_memberships WHERE case_id = ?", smokeCaseId);
            jdbc.update("DELETE FROM custody_cases WHERE id = ?", smokeCaseId);
        }
        if (smokeOperatorId != null) {
            jdbc.update("DELETE FROM operators WHERE id = ?", smokeOperatorId);
        }
    }

    @Test
    void anEmptyDatabaseReachesTheFinalSchemaAndPassesEveryStartupGate() {
        try (MigrationSchemaHarness harness =
                new MigrationSchemaHarness(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            assertThat(harness.tableNames())
                    .as("the certification must start from an empty database")
                    .isEmpty();
            assertThat(harness.hasSchemaHistoryTable()).isFalse();

            try (ConfigurableApplicationContext context = harness.startApplication()) {
                assertThat(context.isRunning())
                        .as("Flyway migration and Hibernate schema validation are startup gates")
                        .isTrue();

                MigrationHistoryAssertions.assertMatchesInventory(harness.history(), MigrationInventory.FINAL_VERSION);
                assertThat(harness.tableNames()).isEqualTo(EXPECTED_TABLES);
                assertThat(harness.count("SELECT COUNT(*) FROM custody_events")).isZero();
                assertThat(harness.count("SELECT COUNT(*) FROM digital_evidence"))
                        .isZero();
            }

            // Flyway validation is re-run against the finished schema exactly as it runs before readiness.
            harness.flyway(null).validate();
        }
    }

    @Test
    void theApplicationOnAFreshlyMigratedDatabaseIsReadyAndServesARepresentativeApiSmoke() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        String username = "smoke.admin." + UUID.randomUUID().toString().substring(0, 8);
        smokeOperatorId = UUID.randomUUID();
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        jdbc.update(
                """
                INSERT INTO operators (
                    id, username, email, password_hash, first_name, last_name, role, status,
                    created_at, updated_at, version
                ) VALUES (?, ?, ?, ?, 'Smoke', 'Admin', 'ADMIN', 'ACTIVE', ?, ?, 0)
                """,
                smokeOperatorId,
                username,
                username + "@example.test",
                passwordEncoder.encode(PASSWORD),
                Timestamp.from(now),
                Timestamp.from(now));

        String loginBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(new LoginPayload(username, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = jsonMapper.readTree(loginBody).get("accessToken").asText();
        String bearer = "Bearer " + token;

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username));

        String created = mockMvc.perform(post("/api/v1/cases")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Empty database certification","priority":"HIGH"}
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode createdCase = jsonMapper.readTree(created);
        smokeCaseId = UUID.fromString(createdCase.get("id").asText());

        mockMvc.perform(get("/api/v1/cases/{caseId}", smokeCaseId).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Empty database certification"));
    }

    private record LoginPayload(String username, String password) {}
}
