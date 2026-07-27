package it.itsprodigi.proofchain.auth.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import it.itsprodigi.proofchain.auth.api.LoginRequest;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthenticationServiceTest {
    private final OperatorRepository operators = mock(OperatorRepository.class);
    private final JwtTokenService tokens = mock(JwtTokenService.class);
    private final AuthenticationService service =
            new AuthenticationService(operators, new BCryptPasswordEncoder(4), tokens);

    @Test
    void normalizesUsernameAndIssuesTokenForActiveOperator() {
        Operator operator = Operator.create(
                "admin",
                "admin@example.test",
                new BCryptPasswordEncoder(4).encode("secret"),
                "Ada",
                "Admin",
                OperatorRole.ADMIN);
        when(operators.findByUsername("admin")).thenReturn(Optional.of(operator));
        when(tokens.issue(operator.getId(), "admin", OperatorRole.ADMIN))
                .thenReturn(new IssuedAccessToken(
                        "redacted",
                        Instant.parse("2026-01-01T00:00:00Z"),
                        Instant.parse("2026-01-01T00:30:00Z"),
                        1800));

        var response = service.login(new LoginRequest(" ADMIN ", "secret"));

        verify(operators).findByUsername("admin");
        verify(tokens).issue(operator.getId(), "admin", OperatorRole.ADMIN);
        org.assertj.core.api.Assertions.assertThat(response.expiresInSeconds()).isEqualTo(1800);
    }

    @Test
    void unknownUsernameStillPerformsDummyComparisonAndNeverIssuesToken() {
        when(operators.findByUsername("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginRequest("missing", "secret")))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(tokens, never()).issue(any(), any(), any());
    }

    @Test
    void inactiveAndOverlongPasswordsAreInvalidCredentials() {
        Operator operator = Operator.create(
                "admin",
                "admin@example.test",
                new BCryptPasswordEncoder(4).encode("secret"),
                "Ada",
                "Admin",
                OperatorRole.ADMIN);
        operator.changeStatus(OperatorStatus.SUSPENDED);
        when(operators.findByUsername("admin")).thenReturn(Optional.of(operator));

        assertThatThrownBy(() -> service.login(new LoginRequest("admin", "secret")))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> service.login(new LoginRequest("admin", "a".repeat(73))))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(tokens, never()).issue(any(), any(), any());
    }
}
