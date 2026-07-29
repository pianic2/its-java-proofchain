package it.itsprodigi.proofchain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import it.itsprodigi.proofchain.auth.api.LoginRequest;
import it.itsprodigi.proofchain.auth.application.JwtTokenService;
import it.itsprodigi.proofchain.operator.application.BootstrapAdminProperties;
import it.itsprodigi.proofchain.operator.application.BootstrapAdminService;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationVerticalSliceIT extends PostgreSqlIntegrationTest {

    private static final String BOOTSTRAP_PASSWORD = "bootstrap-password";
    private static final String OPERATOR_PASSWORD = "operator-password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OperatorRepository operators;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenService tokens;

    @Autowired
    private SecretKey jwtKey;

    @Autowired
    private BootstrapAdminService bootstrapAdminService;

    @Autowired
    private BootstrapAdminProperties bootstrapProperties;

    @Autowired
    private JsonMapper jsonMapper;

    @BeforeEach
    void cleanOperatorsAndConfigureBootstrap() {
        operators.deleteAll();
        bootstrapProperties.setEnabled(true);
        bootstrapProperties.setUsername("  Admin.One  ");
        bootstrapProperties.setEmail("  Admin.One@Example.COM  ");
        bootstrapProperties.setPassword(BOOTSTRAP_PASSWORD);
    }

    @Test
    void bootstrapsAuthenticatesAndExposesTheDatabaseBackedOperator() throws Exception {
        bootstrapAdminService.bootstrap();

        Operator admin = operators.findAll().getFirst();
        assertThat(operators.count()).isEqualTo(1);
        assertThat(admin.getUsername()).isEqualTo("admin.one");
        assertThat(admin.getEmail()).isEqualTo("admin.one@example.com");
        assertThat(admin.getRole()).isEqualTo(OperatorRole.ADMIN);
        assertThat(admin.getStatus()).isEqualTo(OperatorStatus.ACTIVE);
        assertThat(passwordEncoder.matches(BOOTSTRAP_PASSWORD, admin.getPasswordHash()))
                .isTrue();

        String token = loginToken(" ADMIN.ONE ", BOOTSTRAP_PASSWORD);
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(admin.getId().toString()))
                .andExpect(jsonPath("$.username").value("admin.one"))
                .andExpect(jsonPath("$.email").value("admin.one@example.com"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist());

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme")
                        .value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat")
                        .value("JWT"));
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void rejectsCredentialAndTokenFailuresWithTheFrozenProblemContracts() throws Exception {
        bootstrapAdminService.bootstrap();
        Operator admin = operators.findAll().getFirst();

        assertProblem(
                mockMvc.perform(get("/api/v1/auth/me")),
                "https://proofchain.dev/problems/authentication-required",
                401);
        assertProblem(
                mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer malformed")),
                "https://proofchain.dev/problems/invalid-token",
                401);

        SecretKey otherKey =
                Keys.hmacShaKeyFor("a-different-test-signing-key-with-32-bytes".getBytes(StandardCharsets.UTF_8));
        Instant issued = Instant.now().minusSeconds(60);
        assertProblem(
                mockMvc.perform(get("/api/v1/auth/me")
                        .header(
                                "Authorization",
                                bearer(token(otherKey, "proofchain-api", issued, issued.plusSeconds(1800))))),
                "https://proofchain.dev/problems/invalid-token",
                401);
        assertProblem(
                mockMvc.perform(get("/api/v1/auth/me")
                        .header(
                                "Authorization",
                                bearer(token(jwtKey, "wrong-issuer", issued, issued.plusSeconds(1800))))),
                "https://proofchain.dev/problems/invalid-token",
                401);
        assertProblem(
                mockMvc.perform(get("/api/v1/auth/me")
                        .header(
                                "Authorization",
                                bearer(token(
                                        jwtKey,
                                        "proofchain-api",
                                        issued.minusSeconds(1800),
                                        issued.minusSeconds(900))))),
                "https://proofchain.dev/problems/expired-token",
                401);

        assertInvalidCredentials("missing-user", BOOTSTRAP_PASSWORD);
        assertInvalidCredentials("admin.one", "wrong-password");
        admin.changeStatus(OperatorStatus.SUSPENDED);
        operators.saveAndFlush(admin);
        assertInvalidCredentials("admin.one", BOOTSTRAP_PASSWORD);
    }

    @Test
    void emitsSanitizedAuthenticationEventsWithoutCredentialsOrHeaders() throws Exception {
        bootstrapAdminService.bootstrap();
        Operator admin = operators.findAll().getFirst();
        Operator auditor = operators.saveAndFlush(Operator.create(
                "auditor",
                "auditor@example.com",
                passwordEncoder.encode(OPERATOR_PASSWORD),
                "Audit",
                "Operator",
                OperatorRole.AUDITOR));

        assertInvalidCredentials("admin.one", "sensitive-login-password");
        mockMvc.perform(get("/api/v1/operators")
                        .header(
                                "Authorization",
                                bearer(tokens.issue(auditor.getId(), auditor.getUsername(), auditor.getRole())
                                        .value())))
                .andExpect(status().isForbidden());

        String log = Files.readString(Path.of("auth.log"));
        assertThat(log).contains("event=LOGIN_FAILURE").contains("event=ACCESS_DENIED");
        assertThat(log)
                .doesNotContain("sensitive-login-password")
                .doesNotContain("operator-password")
                .doesNotContain("Authorization")
                .doesNotContain("Bearer ");
        assertThat(log).contains("path=/api/v1/auth/login").contains("path=/api/v1/operators");
        assertThat(log).doesNotContain(admin.getPasswordHash());
    }

    private String loginToken(String username, String password) throws Exception {
        ResultActions result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(new LoginRequest(username, password))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"));
        return jsonMapper
                .readTree(result.andReturn().getResponse().getContentAsString())
                .get("accessToken")
                .asText();
    }

    private void assertInvalidCredentials(String username, String password) throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(new LoginRequest(username, password))))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/invalid-credentials"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("The supplied credentials are invalid."));
    }

    private void assertProblem(ResultActions result, String type, int status) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(type))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String token(SecretKey key, String issuer, Instant issued, Instant expires) {
        return Jwts.builder()
                .subject(operators.findAll().getFirst().getId().toString())
                .claim("username", "admin.one")
                .claim("role", "ADMIN")
                .issuedAt(Date.from(issued))
                .expiration(Date.from(expires))
                .id(java.util.UUID.randomUUID().toString())
                .issuer(issuer)
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}
