package it.itsprodigi.proofchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.itsprodigi.proofchain.auth.application.JwtTokenService;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Actuator boundary for the Compose runtime.
 *
 * <p>Actuator is present for exactly one reason: the container orchestrator needs a readiness signal. These tests pin
 * that decision from both sides — the three probes answer without authentication and carry nothing but a status word,
 * and every other endpoint stays unpublished rather than merely password-protected.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActuatorExposureWebMvcTest extends PostgreSqlIntegrationTest {

    /**
     * Endpoints the subtask explicitly forbids, plus the discovery index that would enumerate whatever is published.
     * They must not exist, not even for an authenticated ADMIN.
     */
    private static final List<String> FORBIDDEN_ENDPOINTS = List.of(
            "/actuator",
            "/actuator/env",
            "/actuator/beans",
            "/actuator/metrics",
            "/actuator/heapdump",
            "/actuator/configprops",
            "/actuator/loggers",
            "/actuator/threaddump",
            "/actuator/mappings",
            "/actuator/info",
            "/actuator/shutdown",
            "/actuator/sbom",
            "/actuator/caches",
            "/actuator/scheduledtasks",
            "/actuator/startup",
            "/actuator/conditions",
            "/actuator/health/db",
            "/actuator/health/evidenceStorage");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtTokenService tokens;

    @Autowired
    OperatorRepository operators;

    private String adminToken;

    @BeforeEach
    void setUp() {
        operators.deleteAll();
        Operator admin = operators.save(Operator.create(
                "actuatoradmin",
                "actuatoradmin@example.org",
                "$2a$10$7EqJtq98hPqEX7fNZaFWoO9h4kQJrE9fGJ8u4Y0sRjXx5f7qXy3yK",
                "Actuator",
                "Admin",
                OperatorRole.ADMIN));
        adminToken = tokens.issue(admin.getId(), admin.getUsername(), admin.getRole())
                .value();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
    void theThreeProbesAnswerWithoutAuthentication(String probe) throws Exception {
        mockMvc.perform(get(probe))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
    void theProbeResponsesCarryNoComponentDetailAndNoInfrastructureValue(String probe) throws Exception {
        String body = mockMvc.perform(get(probe))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body)
                .as("a probe response must never leak infrastructure facts")
                .doesNotContainIgnoringCase("postgres")
                .doesNotContainIgnoringCase("jdbc")
                .doesNotContainIgnoringCase("hibernate")
                .doesNotContainIgnoringCase("validationquery")
                .doesNotContainIgnoringCase("/var/lib")
                .doesNotContainIgnoringCase("version")
                .doesNotContainIgnoringCase("database");
    }

    @Test
    void readinessAggregatesTheDatabaseAndTheEvidenceStorageRoot() throws Exception {
        // Group membership is validated at startup, so a renamed or removed contributor fails the context instead of
        // silently shrinking readiness to "the context started".
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}", true));
    }

    @Test
    void noOtherEndpointIsPublished() throws Exception {
        for (String endpoint : FORBIDDEN_ENDPOINTS) {
            mockMvc.perform(get(endpoint).header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void noOtherEndpointIsReachableAnonymouslyEither() throws Exception {
        for (String endpoint : FORBIDDEN_ENDPOINTS) {
            mockMvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void noActuatorPathAppearsInThePublishedOpenApiDocument() throws Exception {
        String document = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(document)
                .as("the published contract must not advertise the operational probes")
                .doesNotContain("/actuator");
    }
}
