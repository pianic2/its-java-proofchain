package it.itsprodigi.proofchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.jsonwebtoken.Jwts;
import it.itsprodigi.proofchain.auth.application.JwtTokenService;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
@Import(SecurityBoundaryWebMvcTest.Fixtures.FixtureController.class)
class SecurityBoundaryWebMvcTest extends PostgreSqlIntegrationTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtTokenService tokens;

    @Autowired
    SecretKey jwtKey;

    @Autowired
    OperatorRepository repository;

    Operator operator;

    @BeforeEach
    void setup() {
        repository.deleteAll();
        operator = repository.save(Operator.create(
                "boundary",
                "boundary@example.com",
                "$2a$10$7EqJtq98hPqEX7fNZaFWoO9h4kQJrE9fGJ8u4Y0sRjXx5f7qXy3yK",
                "First",
                "Last",
                OperatorRole.AUDITOR));
    }

    @Test
    void publicPathsAndLoginAreNotAuthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/auth/login")).andExpect(status().isNotFound());
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void protectedMissingAndMalformedAreProblem401() throws Exception {
        assertProblem(
                mockMvc.perform(get("/fixture/protected")).andExpect(status().isUnauthorized()),
                "https://proofchain.dev/problems/authentication-required",
                "Authentication required",
                "Authentication is required to access this resource.",
                401,
                "/fixture/protected");
        assertProblem(
                mockMvc.perform(get("/fixture/protected").header("Authorization", "Bearer bad token"))
                        .andExpect(status().isUnauthorized()),
                "https://proofchain.dev/problems/invalid-token",
                "Invalid token",
                "The bearer token is invalid.",
                401,
                "/fixture/protected");
    }

    @Test
    void expiredTokenHasExactProblemDetails() throws Exception {
        Date issued = new Date(System.currentTimeMillis() - 10_000);
        String expired = Jwts.builder()
                .subject(operator.getId().toString())
                .claim("username", operator.getUsername())
                .claim("role", "AUDITOR")
                .issuedAt(issued)
                .expiration(new Date(System.currentTimeMillis() - 1_000))
                .id(java.util.UUID.randomUUID().toString())
                .issuer("proofchain-api")
                .signWith(jwtKey, Jwts.SIG.HS256)
                .compact();
        assertProblem(
                mockMvc.perform(get("/fixture/protected").header("Authorization", "Bearer " + expired))
                        .andExpect(status().isUnauthorized()),
                "https://proofchain.dev/problems/expired-token",
                "Expired token",
                "The bearer token has expired.",
                401,
                "/fixture/protected");
    }

    @Test
    void insufficientRoleAndMethodSecurityAreProblem403() throws Exception {
        String token = tokens.issue(operator.getId(), operator.getUsername(), OperatorRole.ADMIN)
                .value();
        assertProblem(
                mockMvc.perform(get("/fixture/admin").header("Authorization", "Bearer " + token))
                        .andExpect(status().isForbidden()),
                "https://proofchain.dev/problems/access-denied",
                "Access denied",
                "The authenticated operator is not authorized to perform this operation.",
                403,
                "/fixture/admin");
    }

    @Test
    void authenticatedRequestDoesNotCreateSessionOrCookie() throws Exception {
        String token = tokens.issue(operator.getId(), operator.getUsername(), operator.getRole())
                .value();
        var result = mockMvc.perform(get("/fixture/protected").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    void openApiHasGlobalBearerRequirement() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.security[0].bearerAuth").exists());
    }

    private org.springframework.test.web.servlet.ResultActions assertProblem(
            org.springframework.test.web.servlet.ResultActions result,
            String type,
            String title,
            String detail,
            int status,
            String instance)
            throws Exception {
        return result.andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type").value(type))
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.detail").value(detail))
                .andExpect(jsonPath("$.instance").value(instance))
                .andExpect(jsonPath("$.timestamp").exists());
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
            @ResponseBody
            @PreAuthorize("hasRole('ADMIN')")
            String admin() {
                return "admin";
            }
        }
    }
}
