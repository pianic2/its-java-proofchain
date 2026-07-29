package it.itsprodigi.proofchain.operator;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.itsprodigi.proofchain.auth.application.JwtTokenService;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class OperatorSecurityVerticalSliceIT extends PostgreSqlIntegrationTest {

    private static final String PASSWORD = "operator-password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OperatorRepository operators;

    @Autowired
    private JwtTokenService tokens;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Operator admin;
    private Operator auditor;

    @BeforeEach
    void cleanOperators() {
        operators.deleteAll();
        admin = operators.saveAndFlush(operator("admin", "admin@example.com", OperatorRole.ADMIN));
        auditor = operators.saveAndFlush(operator("auditor", "auditor@example.com", OperatorRole.AUDITOR));
    }

    @Test
    void adminCanCreateInspectAndChangeOperatorsWhileNonAdminIsDenied() throws Exception {
        String adminToken = token(admin);
        String auditorToken = token(auditor);

        ResultActions created = mockMvc.perform(post("/api/v1/operators")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("new.operator", "new@example.com", "EVIDENCE_OFFICER")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("new.operator"))
                .andExpect(jsonPath("$.role").value("EVIDENCE_OFFICER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist());
        UUID createdId = UUID.fromString(jsonPathValue(created, "id"));

        mockMvc.perform(get("/api/v1/operators")
                        .header("Authorization", bearer(adminToken))
                        .param("sort", "username,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].username").value("admin"))
                .andExpect(jsonPath("$.sort.field").value("username"))
                .andExpect(jsonPath("$.sort.direction").value("asc"));
        mockMvc.perform(get("/api/v1/operators/{id}", createdId).header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(createdId.toString()));

        mockMvc.perform(patch("/api/v1/operators/{id}/role", createdId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"CASE_MANAGER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("CASE_MANAGER"));
        mockMvc.perform(patch("/api/v1/operators/{id}/status", createdId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        mockMvc.perform(post("/api/v1/operators")
                        .header("Authorization", bearer(auditorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("denied", "denied@example.com", "AUDITOR")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/operators").header("Authorization", bearer(auditorToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/operators/{id}", createdId).header("Authorization", bearer(auditorToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/operators/{id}/role", createdId)
                        .header("Authorization", bearer(auditorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"AUDITOR\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/operators/{id}/status", createdId)
                        .header("Authorization", bearer(auditorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void duplicateAndLastAdminRulesReturnProblemDetails() throws Exception {
        String adminToken = token(admin);
        String duplicateBody = createBody("admin", "new@example.com", "AUDITOR");

        assertConflict(
                mockMvc.perform(post("/api/v1/operators")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(duplicateBody)),
                "https://proofchain.dev/problems/duplicate-resource");
        assertConflict(
                mockMvc.perform(patch("/api/v1/operators/{id}/status", admin.getId())
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}")),
                "https://proofchain.dev/problems/operator-invariant-conflict");
        assertConflict(
                mockMvc.perform(patch("/api/v1/operators/{id}/role", admin.getId())
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"AUDITOR\"}")),
                "https://proofchain.dev/problems/operator-invariant-conflict");
        mockMvc.perform(get("/api/v1/operators/not-a-uuid").header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/validation-error"));
        mockMvc.perform(get("/api/v1/operators/{id}", UUID.randomUUID()).header("Authorization", bearer(adminToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/resource-not-found"));
    }

    @Test
    void alreadyIssuedTokensUseCurrentDatabaseStatusAndRole() throws Exception {
        Operator secondAdmin =
                operators.saveAndFlush(operator("second-admin", "second@example.com", OperatorRole.ADMIN));
        String adminToken = token(admin);
        String secondAdminToken = token(secondAdmin);
        String auditorToken = token(auditor);

        mockMvc.perform(patch("/api/v1/operators/{id}/role", admin.getId())
                        .header("Authorization", bearer(secondAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"AUDITOR\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/operators").header("Authorization", bearer(adminToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/operators/{id}/status", auditor.getId())
                        .header("Authorization", bearer(secondAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(auditorToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/invalid-token"));

        mockMvc.perform(patch("/api/v1/operators/{id}/status", auditor.getId())
                        .header("Authorization", bearer(secondAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(auditorToken)))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/operators/{id}/status", auditor.getId())
                        .header("Authorization", bearer(secondAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(auditorToken)))
                .andExpect(status().isUnauthorized());
    }

    private Operator operator(String username, String email, OperatorRole role) {
        return Operator.create(username, email, passwordEncoder.encode(PASSWORD), "First", "Last", role);
    }

    private String token(Operator operator) {
        return tokens.issue(operator.getId(), operator.getUsername(), operator.getRole())
                .value();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String createBody(String username, String email, String role) {
        return """
                {
                  "username": "%s",
                  "email": "%s",
                  "password": "%s",
                  "firstName": "New",
                  "lastName": "Operator",
                  "role": "%s"
                }
                """.formatted(username, email, PASSWORD, role);
    }

    private String jsonPathValue(ResultActions result, String field) throws Exception {
        return new tools.jackson.databind.json.JsonMapper()
                .readTree(result.andReturn().getResponse().getContentAsString())
                .get(field)
                .asText();
    }

    private void assertConflict(ResultActions result, String type) throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(type))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
