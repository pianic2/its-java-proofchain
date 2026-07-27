package it.itsprodigi.proofchain;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.itsprodigi.proofchain.auth.application.JwtTokenService;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
@Import(DatabaseBackedAuthenticationIT.Fixtures.FixtureController.class)
class DatabaseBackedAuthenticationIT extends PostgreSqlIntegrationTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtTokenService tokens;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoSpyBean
    OperatorRepository repository;

    Operator operator;

    @BeforeEach
    void setup() {
        repository.deleteAll();
        operator = repository.save(Operator.create(
                "dbuser",
                "dbuser@example.com",
                "$2a$10$7EqJtq98hPqEX7fNZaFWoO9h4kQJrE9fGJ8u4Y0sRjXx5f7qXy3yK",
                "First",
                "Last",
                OperatorRole.ADMIN));
        clearInvocations(repository);
    }

    @Test
    void activeAuthenticatesAndAnonymousDoesNotLookup() throws Exception {
        String token = tokens.issue(operator.getId(), operator.getUsername(), OperatorRole.AUDITOR)
                .value();
        mockMvc.perform(get("/fixture/protected"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/authentication-required"));
        verifyNoInteractions(repository);
        clearInvocations(repository);
        clearInvocations(repository);
        mockMvc.perform(get("/fixture/protected").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        verify(repository, times(1)).findById(operator.getId());
        verifyNoMoreInteractions(repository);
    }

    @Test
    void nextRequestSeesStatusRoleAndDeletionChanges() throws Exception {
        String token = tokens.issue(operator.getId(), operator.getUsername(), OperatorRole.ADMIN)
                .value();
        authenticated(token, "/fixture/protected", 200);
        jdbc.update("update operators set status='SUSPENDED' where id=?", operator.getId());
        authenticated(token, "/fixture/protected", 401);
        jdbc.update("update operators set status='ACTIVE', role='AUDITOR' where id=?", operator.getId());
        authenticated(token, "/fixture/protected", 200);
        jdbc.update("update operators set status='DISABLED' where id=?", operator.getId());
        authenticated(token, "/fixture/protected", 401);
        jdbc.update("update operators set status='ACTIVE' where id=?", operator.getId());
        jdbc.update("delete from operators where id=?", operator.getId());
        authenticated(token, "/fixture/protected", 401);
    }

    @Test
    void databaseRoleOverridesClaimAndDemotionRemovesAdminAccess() throws Exception {
        String token = tokens.issue(operator.getId(), operator.getUsername(), OperatorRole.AUDITOR)
                .value();
        authenticated(token, "/fixture/admin", 200);
        jdbc.update("update operators set role='AUDITOR' where id=?", operator.getId());
        authenticated(token, "/fixture/admin", 403);
    }

    private void authenticated(String token, String path, int expectedStatus) throws Exception {
        clearInvocations(repository);
        var result = mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().is(expectedStatus));
        if (expectedStatus == 401) {
            result.andExpect(content().contentType("application/problem+json"))
                    .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/invalid-token"))
                    .andExpect(jsonPath("$.title").value("Invalid token"))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.detail").value("The bearer token is invalid."))
                    .andExpect(jsonPath("$.instance").value(path))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
        verify(repository, times(1)).findById(operator.getId());
        verifyNoMoreInteractions(repository);
    }

    @TestConfiguration
    static class Fixtures {
        @RestController
        static class FixtureController {
            @GetMapping("/fixture/protected")
            @ResponseBody
            String protectedEndpoint() {
                return "ok";
            }

            @GetMapping("/fixture/admin")
            @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
            @ResponseBody
            String adminEndpoint() {
                return "admin";
            }
        }
    }
}
