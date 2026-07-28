package it.itsprodigi.proofchain.operator.application;

import it.itsprodigi.proofchain.auth.logging.AuthEventLogger;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorNormalizer;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import java.util.Objects;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class BootstrapAdminService {

    private final BootstrapAdminProperties properties;
    private final OperatorRepository operatorRepository;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final AuthEventLogger authEventLogger;

    public BootstrapAdminService(
            BootstrapAdminProperties properties,
            OperatorRepository operatorRepository,
            PasswordPolicy passwordPolicy,
            PasswordEncoder passwordEncoder,
            AuthEventLogger authEventLogger) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.operatorRepository = Objects.requireNonNull(operatorRepository, "operatorRepository must not be null");
        this.passwordPolicy = Objects.requireNonNull(passwordPolicy, "passwordPolicy must not be null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
        this.authEventLogger = Objects.requireNonNull(authEventLogger, "authEventLogger must not be null");
    }

    @Transactional
    public void bootstrap() {
        if (!properties.isEnabled()) {
            authEventLogger.bootstrapAdminSkipped(null, "BOOTSTRAP_DISABLED");
            return;
        }
        var activeAdmins = operatorRepository.lockActiveAdmins();
        if (!activeAdmins.isEmpty()) {
            authEventLogger.bootstrapAdminSkipped(activeAdmins.getFirst(), "ACTIVE_ADMIN_EXISTS");
            return;
        }
        validateConfiguration();

        String username = OperatorNormalizer.normalizeUsername(properties.getUsername());
        String email = OperatorNormalizer.normalizeEmail(properties.getEmail());
        passwordPolicy.validate(properties.getPassword());
        String encodedPassword = passwordEncoder.encode(properties.getPassword());
        Operator operator =
                Operator.create(username, email, encodedPassword, "Initial", "Administrator", OperatorRole.ADMIN);
        operatorRepository.save(operator);
        logCompletionAfterCommit(operator);
    }

    private void validateConfiguration() {
        if (isBlank(properties.getUsername())) {
            throw new IllegalStateException("Bootstrap administrator configuration is incomplete: username");
        }
        if (isBlank(properties.getEmail())) {
            throw new IllegalStateException("Bootstrap administrator configuration is incomplete: email");
        }
        if (isBlank(properties.getPassword())) {
            throw new IllegalStateException("Bootstrap administrator configuration is incomplete: password");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void logCompletionAfterCommit(Operator operator) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            authEventLogger.bootstrapAdminCompleted(operator);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                authEventLogger.bootstrapAdminCompleted(operator);
            }
        });
    }
}
