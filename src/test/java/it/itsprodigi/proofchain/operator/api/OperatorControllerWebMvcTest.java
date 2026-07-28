package it.itsprodigi.proofchain.operator.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class OperatorControllerWebMvcTest extends PostgreSqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private OperatorRepository operators;

    @Autowired
    private JwtTokenService tokens;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Operator admin;
    private Operator auditor;

    @BeforeEach
    void setUp() {
        operators.deleteAll();
        admin = operators.saveAndFlush(operator("admin", "admin@example.com", OperatorRole.ADMIN));
        auditor = operators.saveAndFlush(operator("auditor", "auditor@example.com", OperatorRole.AUDITOR));
    }

    @Test
    void adminCreateReturns201LocationAndDetailWithoutSensitiveFields() throws Exception {
        mockMvc.perform(post("/api/v1/operators")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": " New.User ",
                                  "email": " New.User@Example.COM ",
                                  "password": "secure-password",
                                  "firstName": " New ",
                                  "lastName": " User ",
                                  "role": "CASE_MANAGER"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern("https?://[^/]+/api/v1/operators/[0-9a-f-]{36}")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.*", hasSize(9)))
                .andExpect(jsonPath("$.username").value("new.user"))
                .andExpect(jsonPath("$.email").value("new.user@example.com"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist());
    }

    @Test
    void listReturnsTheApprovedExplicitPageDto() throws Exception {
        mockMvc.perform(get("/api/v1/operators")
                        .header("Authorization", bearer(admin))
                        .param("page", "0")
                        .param("size", "20")
                        .param("sort", "username,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(6)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.sort.field").value("username"))
                .andExpect(jsonPath("$.sort.direction").value("asc"))
                .andExpect(jsonPath("$.content[0].username").value("admin"))
                .andExpect(jsonPath("$.content[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$.content[0].version").doesNotExist());
    }

    @Test
    void detailReturnsTheApprovedDetailDto() throws Exception {
        mockMvc.perform(get("/api/v1/operators/{id}", auditor.getId()).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(9)))
                .andExpect(jsonPath("$.id").value(auditor.getId().toString()))
                .andExpect(jsonPath("$.role").value("AUDITOR"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist());
    }

    @Test
    void roleAndStatusPatchReturnDetailDtos() throws Exception {
        mockMvc.perform(patch("/api/v1/operators/{id}/role", auditor.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"CASE_MANAGER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("CASE_MANAGER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").doesNotExist());

        mockMvc.perform(patch("/api/v1/operators/{id}/status", auditor.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("CASE_MANAGER"))
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void nonAdminReceives403OnAllFiveEndpoints() throws Exception {
        String token = bearer(auditor);
        mockMvc.perform(post("/api/v1/operators")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/operators").header("Authorization", token)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/operators/{id}", auditor.getId()).header("Authorization", token))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/operators/{id}/role", auditor.getId())
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"AUDITOR\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/operators/{id}/status", auditor.getId())
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousRequestReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/operators"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/authentication-required"));
    }

    @Test
    void duplicateIdentityReturnsTheApproved409Problem() throws Exception {
        mockMvc.perform(post("/api/v1/operators")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("admin", "new@example.com")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/duplicate-resource"))
                .andExpect(jsonPath("$.title").value("Duplicate resource"))
                .andExpect(
                        jsonPath("$.detail").value("An operator with the supplied username or email already exists."));
    }

    @Test
    void selfAdministrationAndLastAdminInvariantReturn409() throws Exception {
        mockMvc.perform(patch("/api/v1/operators/{id}/status", admin.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/operator-invariant-conflict"))
                .andExpect(jsonPath("$.detail").value("An ADMIN cannot suspend or disable itself."));
        mockMvc.perform(patch("/api/v1/operators/{id}/role", admin.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"AUDITOR\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Self-demotion requires another ACTIVE ADMIN."));
    }

    @Test
    void optimisticLockConflictReturns409Problem() throws Exception {
        doThrow(new OptimisticLockingFailureException("forced optimistic-lock conflict"))
                .when(operators)
                .flush();

        mockMvc.perform(patch("/api/v1/operators/{id}/role", auditor.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"CASE_MANAGER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/concurrent-modification"))
                .andExpect(jsonPath("$.title").value("Concurrent modification"))
                .andExpect(jsonPath("$.detail")
                        .value("The operator was modified by another transaction. Retry using current data."));
    }

    @Test
    void alreadyIssuedTokensLoseAccessAfterDemotionAndSuspension() throws Exception {
        Operator secondAdmin =
                operators.saveAndFlush(operator("second-admin", "second@example.com", OperatorRole.ADMIN));
        String demotedToken = bearer(admin);
        String suspendedToken = bearer(auditor);

        mockMvc.perform(patch("/api/v1/operators/{id}/role", admin.getId())
                        .header("Authorization", bearer(secondAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"AUDITOR\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/operators").header("Authorization", demotedToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/operators/{id}/status", auditor.getId())
                        .header("Authorization", bearer(secondAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/operators").header("Authorization", suspendedToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/invalid-token"));
    }

    @Test
    void invalidUuidEnumPageSizeAndSortReturnValidation400() throws Exception {
        mockMvc.perform(get("/api/v1/operators/not-a-uuid").header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/validation-error"));
        mockMvc.perform(patch("/api/v1/operators/{id}/role", auditor.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"SUPER_ADMIN\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/operators")
                        .header("Authorization", bearer(admin))
                        .param("size", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/operators")
                        .header("Authorization", bearer(admin))
                        .param("sort", "unknown,asc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingOperatorReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/operators/{id}", UUID.randomUUID()).header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/resource-not-found"));
    }

    @Test
    void noHardDeleteEndpointIsExposed() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/operators/{id}'].delete").doesNotExist());
    }

    @Test
    void openApiDocumentsOperatorSecuritySchemasResponsesAndMediaTypes() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/operators'].post.security[0].bearerAuth")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/v1/operators'].post.responses['201'].content['application/json']")
                        .exists())
                .andExpect(jsonPath(
                                "$.paths['/api/v1/operators'].post.responses['400'].content['application/problem+json']")
                        .exists())
                .andExpect(jsonPath(
                                "$.paths['/api/v1/operators'].post.responses['409'].content['application/problem+json']")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/v1/operators'].get.responses['200'].content['application/json']")
                        .exists())
                .andExpect(jsonPath(
                                "$.paths['/api/v1/operators'].get.responses['400'].content['application/problem+json']")
                        .exists())
                .andExpect(jsonPath(
                                "$.paths['/api/v1/operators/{id}'].get.responses['404'].content['application/problem+json']")
                        .exists())
                .andExpect(jsonPath(
                                "$.paths['/api/v1/operators/{id}/role'].patch.responses['200'].content['application/json']")
                        .exists())
                .andExpect(jsonPath(
                                "$.paths['/api/v1/operators/{id}/role'].patch.responses['409'].content['application/problem+json']")
                        .exists())
                .andExpect(jsonPath(
                                "$.paths['/api/v1/operators/{id}/status'].patch.responses['200'].content['application/json']")
                        .exists())
                .andExpect(jsonPath(
                                "$.paths['/api/v1/operators/{id}/status'].patch.responses['409'].content['application/problem+json']")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.OperatorDetailResponse.properties.passwordHash")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.OperatorDetailResponse.properties.version")
                        .doesNotExist());
    }

    private String bearer(Operator operator) {
        return "Bearer "
                + tokens.issue(operator.getId(), operator.getUsername(), operator.getRole())
                        .value();
    }

    private Operator operator(String username, String email, OperatorRole role) {
        return Operator.create(username, email, passwordEncoder.encode("correct-password"), "First", "Last", role);
    }

    private String createBody() {
        return createBody("new.operator", "new@example.com");
    }

    private String createBody(String username, String email) {
        return """
                {
                  "username": "%s",
                  "email": "%s",
                  "password": "secure-password",
                  "firstName": "New",
                  "lastName": "Operator",
                  "role": "AUDITOR"
                }
                """.formatted(username, email);
    }
}
