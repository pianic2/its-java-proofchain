package it.itsprodigi.proofchain.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import it.itsprodigi.proofchain.auth.application.JwtTokenService;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationConcurrencyIT extends PostgreSqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OperatorRepository operators;

    @Autowired
    private JwtTokenService tokens;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private ExecutorService executor;
    private Operator firstAdmin;
    private Operator secondAdmin;

    @BeforeEach
    void cleanOperators() {
        operators.deleteAll();
        firstAdmin = operators.saveAndFlush(operator("first-admin", "first@example.com"));
        secondAdmin = operators.saveAndFlush(operator("second-admin", "second@example.com"));
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopExecutor() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSelfDemotionsLeaveExactlyOneActiveAdmin() throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        Future<Integer> first = executor.submit(() -> selfDemoteAfter(start, firstAdmin));
        Future<Integer> second = executor.submit(() -> selfDemoteAfter(start, secondAdmin));

        List<Integer> statuses = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(operators.countActiveAdmins()).isEqualTo(1);
    }

    private int selfDemoteAfter(CyclicBarrier start, Operator admin) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        return mockMvc.perform(patch("/api/v1/operators/{id}/role", admin.getId())
                        .header(
                                "Authorization",
                                bearer(tokens.issue(admin.getId(), admin.getUsername(), OperatorRole.ADMIN)
                                        .value()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"AUDITOR\"}"))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private Operator operator(String username, String email) {
        return Operator.create(
                username, email, passwordEncoder.encode("concurrency-password"), "First", "Admin", OperatorRole.ADMIN);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
