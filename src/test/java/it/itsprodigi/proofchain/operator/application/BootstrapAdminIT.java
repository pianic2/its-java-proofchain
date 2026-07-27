package it.itsprodigi.proofchain.operator.application;

import static org.assertj.core.api.Assertions.assertThat;

import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class BootstrapAdminIT extends PostgreSqlIntegrationTest {

    private static final String PASSWORD = "p".repeat(12);

    @Autowired
    private BootstrapAdminService service;

    @Autowired
    private BootstrapAdminProperties properties;

    @Autowired
    private OperatorRepository repository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @DynamicPropertySource
    static void bootstrapProperties(DynamicPropertyRegistry registry) {
        registry.add("proofchain.bootstrap.admin.enabled", () -> "true");
        registry.add("proofchain.bootstrap.admin.username", () -> "  Admin.One  ");
        registry.add("proofchain.bootstrap.admin.email", () -> "  Admin.One@Example.COM  ");
        registry.add("proofchain.bootstrap.admin.password", () -> PASSWORD);
    }

    @BeforeEach
    void cleanOperators() {
        repository.deleteAll();
        properties.setEnabled(true);
        properties.setUsername("  Admin.One  ");
        properties.setEmail("  Admin.One@Example.COM  ");
        properties.setPassword(PASSWORD);
    }

    @Test
    void enabledBootstrapCreatesNormalizedActiveAdminWithBcryptPassword() {
        service.bootstrap();

        Operator operator = repository.findAll().getFirst();
        assertThat(repository.count()).isEqualTo(1);
        assertThat(operator.getUsername()).isEqualTo("admin.one");
        assertThat(operator.getEmail()).isEqualTo("admin.one@example.com");
        assertThat(operator.getRole()).isEqualTo(it.itsprodigi.proofchain.operator.domain.OperatorRole.ADMIN);
        assertThat(operator.getStatus()).isEqualTo(it.itsprodigi.proofchain.operator.domain.OperatorStatus.ACTIVE);
        assertThat(operator.getPasswordHash()).startsWith("$2").hasSize(60);
        assertThat(passwordEncoder.matches(PASSWORD, operator.getPasswordHash()))
                .isTrue();
    }

    @Test
    void disabledBootstrapLeavesOperatorsTableEmpty() {
        properties.setEnabled(false);

        service.bootstrap();

        assertThat(repository.count()).isZero();
    }

    @Test
    void sequentialBootstrapIsIdempotent() {
        service.bootstrap();
        var first = repository.findAll().getFirst();

        service.bootstrap();

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findAll().getFirst().getId()).isEqualTo(first.getId());
    }

    @Test
    void existingOperatorRemainsUnchanged() {
        service.bootstrap();
        Operator existing = repository.findAll().getFirst();
        String originalEmail = existing.getEmail();
        var originalId = existing.getId();

        properties.setUsername("another-admin");
        properties.setEmail("another@example.com");
        service.bootstrap();

        Operator unchanged = repository.findById(originalId).orElseThrow();
        assertThat(repository.count()).isEqualTo(1);
        assertThat(unchanged.getEmail()).isEqualTo(originalEmail);
        assertThat(unchanged.getUsername()).isEqualTo("admin.one");
    }
}
