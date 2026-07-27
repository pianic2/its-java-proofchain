package it.itsprodigi.proofchain.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.itsprodigi.proofchain.auth.application.AuthenticationService;
import it.itsprodigi.proofchain.auth.application.InvalidCredentialsException;
import it.itsprodigi.proofchain.auth.application.JwtTokenService;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerWebMvcTest extends PostgreSqlIntegrationTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    OperatorRepository operators;

    @Autowired
    JwtTokenService tokens;

    @Autowired
    PasswordEncoder passwordEncoder;

    @MockitoBean
    AuthenticationService authenticationService;

    Operator operator;

    @BeforeEach
    void setUp() {
        operators.deleteAll();
        operator = operators.save(Operator.create(
                "admin",
                "admin@example.test",
                passwordEncoder.encode("correct-password"),
                "Ada",
                "Admin",
                OperatorRole.ADMIN));
    }

    @Test
    void validLoginReturnsExactJsonAndCachePreventionHeaders() throws Exception {
        when(authenticationService.login(new LoginRequest("admin", "correct-password")))
                .thenReturn(new LoginResponse("redacted", "Bearer", Instant.parse("2026-01-01T00:30:00Z"), 1800));

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"correct-password\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.*", hasSize(4)))
                .andExpect(jsonPath("$.accessToken").value("redacted"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresAt").value("2026-01-01T00:30:00Z"))
                .andExpect(jsonPath("$.expiresInSeconds").value(1800));
    }

    @Test
    void blankFieldsReturnValidationProblemWithoutEchoingPassword() throws Exception {
        var usernameResult = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"   \",\"password\":\"sensitive-value\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/validation-error"))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("One or more request fields are invalid."))
                .andExpect(jsonPath("$.instance").value("/api/v1/auth/login"))
                .andExpect(jsonPath("$.errors[0].field").value("username"))
                .andReturn();
        assertThat(usernameResult.getResponse().getContentAsString()).doesNotContain("sensitive-value");

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("password"));

        verifyNoInteractions(authenticationService);
    }

    @Test
    void allCredentialFailuresExposeTheSamePublicProblemContract() throws Exception {
        when(authenticationService.login(any(LoginRequest.class))).thenThrow(new InvalidCredentialsException());

        assertInvalidCredentials("{\"username\":\"missing\",\"password\":\"secret\"}");
        assertInvalidCredentials("{\"username\":\"admin\",\"password\":\"wrong\"}");
        assertInvalidCredentials("{\"username\":\"suspended\",\"password\":\"secret\"}");
        assertInvalidCredentials("{\"username\":\"admin\",\"password\":\"ééééééééééééééééééééééééééééééééééééé\"}");
    }

    @Test
    void authenticatedMeReturnsOnlyApprovedDatabaseBackedFields() throws Exception {
        String token = tokens.issue(operator.getId(), operator.getUsername(), operator.getRole())
                .value();

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.*", hasSize(9)))
                .andExpect(jsonPath("$.id").value(operator.getId().toString()))
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.email").value("admin@example.test"))
                .andExpect(jsonPath("$.firstName").value("Ada"))
                .andExpect(jsonPath("$.lastName").value("Admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").value(operator.getCreatedAt().toString()))
                .andExpect(jsonPath("$.updatedAt").value(operator.getUpdatedAt().toString()))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist());
    }

    @Test
    void meWithoutAuthenticationReturnsAuthenticationRequiredProblem() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/authentication-required"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void openApiMarksLoginPublicAndMeBearerProtected() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.paths['/api/v1/auth/login'].post.security").isArray())
                .andExpect(
                        jsonPath("$.paths['/api/v1/auth/login'].post.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.responses['200'].content['application/json']")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/me'].get.security[0].bearerAuth")
                        .isArray())
                .andExpect(jsonPath("$.paths['/api/v1/auth/me'].get.responses['200'].content['application/json']")
                        .exists());
    }

    private ResultActions assertInvalidCredentials(String body) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/invalid-credentials"))
                .andExpect(jsonPath("$.title").value("Invalid credentials"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("The supplied credentials are invalid."))
                .andExpect(jsonPath("$.instance").value("/api/v1/auth/login"))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
