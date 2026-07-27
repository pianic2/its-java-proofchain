package it.itsprodigi.proofchain.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.itsprodigi.proofchain.auth.application.JwtTokenService;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
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
class AuthenticationFlowIT extends PostgreSqlIntegrationTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    OperatorRepository operators;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JwtTokenService tokens;

    @Autowired
    JsonMapper jsonMapper;

    Operator operator;

    @BeforeEach
    void setUp() {
        operators.deleteAll();
        operator = operators.saveAndFlush(Operator.create(
                "admin",
                "admin@example.test",
                passwordEncoder.encode("correct-password"),
                "Ada",
                "Admin",
                OperatorRole.ADMIN));
    }

    @Test
    void activeOperatorCanLoginAndUseTheIssuedTokenForMe() throws Exception {
        assertThat(passwordEncoder.matches("correct-password", operator.getPasswordHash())).isTrue();

        var login = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(new LoginRequest(" ADMIN ", "correct-password"))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").value(1800))
                .andReturn();

        String token = jsonMapper.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
        var claims = tokens.validate(token);
        assertThat(claims.operatorId()).isEqualTo(operator.getId());
        assertThat(claims.username()).isEqualTo("admin");
        assertThat(claims.role()).isEqualTo(OperatorRole.ADMIN);

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(operator.getId().toString()))
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.email").value("admin@example.test"))
                .andExpect(jsonPath("$.firstName").value("Ada"))
                .andExpect(jsonPath("$.lastName").value("Admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist());
    }

    @Test
    void suspendedAndDisabledOperatorsCannotLogin() throws Exception {
        operator.changeStatus(OperatorStatus.SUSPENDED);
        operators.saveAndFlush(operator);
        assertInvalidCredentials("admin", "correct-password");

        operator.changeStatus(OperatorStatus.DISABLED);
        operators.saveAndFlush(operator);
        assertInvalidCredentials("admin", "correct-password");
    }

    @Test
    void unknownUsernameWrongPasswordAndOverlongPasswordShareThePublicContract() throws Exception {
        assertInvalidCredentials("missing", "correct-password");
        assertInvalidCredentials("admin", "wrong-password");
        assertInvalidCredentials("admin", "é".repeat(37));
    }

    private ResultActions assertInvalidCredentials(String username, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(new LoginRequest(username, password))))
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
