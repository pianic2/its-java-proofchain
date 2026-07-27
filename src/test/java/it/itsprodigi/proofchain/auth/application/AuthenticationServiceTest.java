package it.itsprodigi.proofchain.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import it.itsprodigi.proofchain.auth.api.LoginRequest;
import it.itsprodigi.proofchain.auth.api.LoginResponse;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthenticationServiceTest {
    private static final String DUMMY_PASSWORD = "proofchain-dummy-credential";
    private static final String DUMMY_HASH = "dummy-hash";
    private static final String OPERATOR_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoO9h4kQJrE9fGJ8u4Y0sRjXx5f7qXy3yK";

    private final OperatorRepository operators = mock(OperatorRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final JwtTokenService tokens = mock(JwtTokenService.class);
    private AuthenticationService service;

    @BeforeEach
    void setUp() {
        when(passwordEncoder.encode(DUMMY_PASSWORD)).thenReturn(DUMMY_HASH);
        service = new AuthenticationService(operators, passwordEncoder, tokens);
    }

    @Test
    void normalizesUsernameAndMapsIssuedTokenForActiveOperator() {
        Operator operator = operator(OperatorStatus.ACTIVE);
        IssuedAccessToken issued = new IssuedAccessToken(
                "redacted", Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:30:00Z"), 1800);
        when(operators.findByUsername("admin")).thenReturn(Optional.of(operator));
        when(passwordEncoder.matches("secret", OPERATOR_HASH)).thenReturn(true);
        when(tokens.issue(operator.getId(), "admin", OperatorRole.ADMIN)).thenReturn(issued);

        LoginResponse response = service.login(new LoginRequest(" ADMIN ", "secret"));

        assertThat(response)
                .isEqualTo(new LoginResponse("redacted", "Bearer", Instant.parse("2026-01-01T00:30:00Z"), 1800));
        verify(operators).findByUsername("admin");
        verify(passwordEncoder).matches("secret", OPERATOR_HASH);
        verify(tokens).issue(operator.getId(), "admin", OperatorRole.ADMIN);
    }

    @Test
    void unknownUsernameUsesPreparedDummyComparisonAndNeverIssuesToken() {
        when(operators.findByUsername("missing")).thenReturn(Optional.empty());
        when(passwordEncoder.matches("secret", DUMMY_HASH)).thenReturn(true);

        assertThatThrownBy(() -> service.login(new LoginRequest("missing", "secret")))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(passwordEncoder).encode(DUMMY_PASSWORD);
        verify(passwordEncoder).matches("secret", DUMMY_HASH);
        verify(tokens, never()).issue(any(), any(), any());
    }

    @Test
    void wrongPasswordReturnsInvalidCredentialsAndNeverIssuesToken() {
        Operator operator = operator(OperatorStatus.ACTIVE);
        when(operators.findByUsername("admin")).thenReturn(Optional.of(operator));
        when(passwordEncoder.matches("wrong", OPERATOR_HASH)).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginRequest("admin", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(passwordEncoder).matches("wrong", OPERATOR_HASH);
        verify(tokens, never()).issue(any(), any(), any());
    }

    @Test
    void suspendedOperatorReturnsInvalidCredentialsAfterPasswordComparison() {
        Operator operator = operator(OperatorStatus.SUSPENDED);
        when(operators.findByUsername("admin")).thenReturn(Optional.of(operator));
        when(passwordEncoder.matches("secret", OPERATOR_HASH)).thenReturn(true);

        assertThatThrownBy(() -> service.login(new LoginRequest("admin", "secret")))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(passwordEncoder).matches("secret", OPERATOR_HASH);
        verify(tokens, never()).issue(any(), any(), any());
    }

    @Test
    void disabledOperatorReturnsInvalidCredentialsAfterPasswordComparison() {
        Operator operator = operator(OperatorStatus.DISABLED);
        when(operators.findByUsername("admin")).thenReturn(Optional.of(operator));
        when(passwordEncoder.matches("secret", OPERATOR_HASH)).thenReturn(true);

        assertThatThrownBy(() -> service.login(new LoginRequest("admin", "secret")))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(passwordEncoder).matches("secret", OPERATOR_HASH);
        verify(tokens, never()).issue(any(), any(), any());
    }

    @Test
    void passwordOverSeventyTwoUtf8BytesStillPerformsDummyComparison() {
        Operator operator = operator(OperatorStatus.ACTIVE);
        String overlongPassword = "é".repeat(37);
        when(operators.findByUsername("admin")).thenReturn(Optional.of(operator));
        when(passwordEncoder.matches(DUMMY_PASSWORD, DUMMY_HASH)).thenReturn(true);

        assertThatThrownBy(() -> service.login(new LoginRequest("admin", overlongPassword)))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(passwordEncoder).matches(DUMMY_PASSWORD, DUMMY_HASH);
        verify(tokens, never()).issue(any(), any(), any());
    }

    @Test
    void loginResponseContainsOnlyApprovedTokenMetadata() {
        assertThat(LoginResponse.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("accessToken", "tokenType", "expiresAt", "expiresInSeconds");
    }

    private Operator operator(OperatorStatus status) {
        Operator operator =
                Operator.create("admin", "admin@example.test", OPERATOR_HASH, "Ada", "Admin", OperatorRole.ADMIN);
        if (status != OperatorStatus.ACTIVE) {
            operator.changeStatus(status);
        }
        return operator;
    }
}
