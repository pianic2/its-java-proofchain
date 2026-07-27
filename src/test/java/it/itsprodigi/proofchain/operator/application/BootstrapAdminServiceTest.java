package it.itsprodigi.proofchain.operator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.itsprodigi.proofchain.common.config.PasswordSecurityProperties;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class BootstrapAdminServiceTest {

    private static final String PASSWORD = "p".repeat(12);
    private TestRepositoryState repositoryState;
    private TestEncoder encoder;
    private BootstrapAdminProperties properties;
    private BootstrapAdminService service;

    @BeforeEach
    void setUp() {
        repositoryState = new TestRepositoryState();
        encoder = new TestEncoder();
        properties = new BootstrapAdminProperties();
        properties.setEnabled(true);
        properties.setUsername("  Admin.One  ");
        properties.setEmail("  Admin.One@Example.COM  ");
        properties.setPassword(PASSWORD);
        PasswordSecurityProperties securityProperties = new PasswordSecurityProperties();
        securityProperties.setBcryptStrength(4);
        PasswordPolicy policy = new PasswordPolicy(securityProperties);
        service = new BootstrapAdminService(properties, repositoryState.repository(), policy, encoder);
    }

    @Test
    void disabledBootstrapDoesNotWrite() {
        properties.setEnabled(false);

        service.bootstrap();

        assertThat(repositoryState.countCalls).isZero();
        assertThat(repositoryState.saveCalls).isZero();
    }

    @Test
    void missingRequiredValuesFailWithoutRepositoryAccess() {
        properties.setUsername(null);

        assertMissingValue("username");
    }

    @Test
    void missingEmailFailsWithoutRepositoryAccess() {
        properties.setEmail(null);

        assertMissingValue("email");
    }

    @Test
    void missingPasswordFailsWithoutRepositoryAccess() {
        properties.setPassword(null);

        assertMissingValue("password");
    }

    private void assertMissingValue(String key) {
        assertThatThrownBy(service::bootstrap)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap administrator configuration is incomplete: " + key);
        assertThat(repositoryState.countCalls).isZero();
        assertThat(repositoryState.saveCalls).isZero();
    }

    @Test
    void existingOperatorsAreSkippedWithoutEncodingOrWriting() {
        repositoryState.count = 1;

        service.bootstrap();

        assertThat(repositoryState.countCalls).isEqualTo(1);
        assertThat(encoder.encodeCalls).isZero();
        assertThat(repositoryState.saveCalls).isZero();
    }

    @Test
    void createsOneNormalizedActiveAdminWithAnEncodedPassword() {
        String encodedPassword = "e".repeat(60);
        encoder.encodedPassword = encodedPassword;

        service.bootstrap();

        Operator operator = repositoryState.saved;
        assertThat(repositoryState.saveCalls).isEqualTo(1);
        assertThat(operator.getUsername()).isEqualTo("admin.one");
        assertThat(operator.getEmail()).isEqualTo("admin.one@example.com");
        assertThat(operator.getPasswordHash()).isEqualTo(encodedPassword);
        assertThat(operator.getFirstName()).isEqualTo("Initial");
        assertThat(operator.getLastName()).isEqualTo("Administrator");
    }

    @Test
    void rejectsInvalidPasswordWithoutEncodingOrWriting() {
        properties.setPassword("short");

        assertThatThrownBy(service::bootstrap).hasMessageContaining("character limits");
        assertThat(encoder.encodeCalls).isZero();
        assertThat(repositoryState.saveCalls).isZero();
    }

    private static final class TestRepositoryState {
        private long count;
        private int countCalls;
        private int saveCalls;
        private Operator saved;

        private OperatorRepository repository() {
            return (OperatorRepository) Proxy.newProxyInstance(
                    OperatorRepository.class.getClassLoader(),
                    new Class<?>[] {OperatorRepository.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("count")) {
                            countCalls++;
                            return count;
                        }
                        if (method.getName().equals("save")) {
                            saveCalls++;
                            saved = (Operator) args[0];
                            return saved;
                        }
                        if (method.getName().equals("toString")) {
                            return "TestRepository";
                        }
                        if (method.getReturnType().isPrimitive()) {
                            return method.getReturnType() == boolean.class ? false : 0;
                        }
                        return null;
                    });
        }
    }

    private static final class TestEncoder implements PasswordEncoder {
        private int encodeCalls;
        private String encodedPassword = "e".repeat(60);

        @Override
        public String encode(CharSequence rawPassword) {
            encodeCalls++;
            return encodedPassword;
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return false;
        }
    }
}
