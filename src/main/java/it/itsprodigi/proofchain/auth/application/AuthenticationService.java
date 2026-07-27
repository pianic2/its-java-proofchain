package it.itsprodigi.proofchain.auth.application;

import it.itsprodigi.proofchain.auth.api.LoginRequest;
import it.itsprodigi.proofchain.auth.api.LoginResponse;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorNormalizer;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticationService {
    private static final String DUMMY_PASSWORD_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoO9h4kQJrE9fGJ8u4Y0sRjXx5f7qXy3yK";
    private final OperatorRepository operators;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokens;

    public AuthenticationService(
            OperatorRepository operators, PasswordEncoder passwordEncoder, JwtTokenService jwtTokens) {
        this.operators = operators;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokens = jwtTokens;
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        String username = OperatorNormalizer.normalizeUsername(request.username());
        Operator operator = operators.findByUsername(username).orElse(null);
        String hash = operator == null ? DUMMY_PASSWORD_HASH : operator.getPasswordHash();
        boolean passwordMatches = request.password().getBytes(StandardCharsets.UTF_8).length <= 72
                && passwordEncoder.matches(request.password(), hash);
        if (operator == null || operator.getStatus() != OperatorStatus.ACTIVE || !passwordMatches) {
            throw new InvalidCredentialsException();
        }
        IssuedAccessToken issued = jwtTokens.issue(operator.getId(), operator.getUsername(), operator.getRole());
        return new LoginResponse(issued.value(), "Bearer", issued.expiresAt(), issued.expiresInSeconds());
    }
}
