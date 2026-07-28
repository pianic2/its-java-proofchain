package it.itsprodigi.proofchain.auth.application;

import it.itsprodigi.proofchain.auth.api.LoginRequest;
import it.itsprodigi.proofchain.auth.api.LoginResponse;
import it.itsprodigi.proofchain.auth.logging.AuthEventLogger;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorNormalizer;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthenticationService {
    private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;
    private static final String DUMMY_PASSWORD = "proofchain-dummy-credential";

    private final OperatorRepository operators;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokens;
    private final AuthEventLogger authEventLogger;
    private final String dummyPasswordHash;

    public AuthenticationService(
            OperatorRepository operators,
            PasswordEncoder passwordEncoder,
            JwtTokenService jwtTokens,
            AuthEventLogger authEventLogger) {
        this.operators = operators;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokens = jwtTokens;
        this.authEventLogger = authEventLogger;
        dummyPasswordHash = passwordEncoder.encode(DUMMY_PASSWORD);
    }

    public LoginResponse login(LoginRequest request) {
        String username = OperatorNormalizer.normalizeUsername(request.username());
        Operator operator = operators.findByUsername(username).orElse(null);
        boolean passwordTooLong =
                request.password().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_PASSWORD_BYTES;
        String candidatePassword = passwordTooLong ? DUMMY_PASSWORD : request.password();
        String expectedHash = operator == null || passwordTooLong ? dummyPasswordHash : operator.getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(candidatePassword, expectedHash);

        if (operator == null || passwordTooLong || operator.getStatus() != OperatorStatus.ACTIVE || !passwordMatches) {
            authEventLogger.loginFailure(operator, "INVALID_CREDENTIALS");
            throw new InvalidCredentialsException();
        }

        IssuedAccessToken issued = jwtTokens.issue(operator.getId(), operator.getUsername(), operator.getRole());
        authEventLogger.loginSuccess(operator);
        return new LoginResponse(issued.value(), "Bearer", issued.expiresAt(), issued.expiresInSeconds());
    }
}
