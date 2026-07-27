package it.itsprodigi.proofchain.operator.application;

import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorNormalizer;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BootstrapAdminService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BootstrapAdminService.class);
    private final BootstrapAdminProperties properties;
    private final OperatorRepository operatorRepository;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;

    public BootstrapAdminService(
            BootstrapAdminProperties properties,
            OperatorRepository operatorRepository,
            PasswordPolicy passwordPolicy,
            PasswordEncoder passwordEncoder) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.operatorRepository = Objects.requireNonNull(operatorRepository, "operatorRepository must not be null");
        this.passwordPolicy = Objects.requireNonNull(passwordPolicy, "passwordPolicy must not be null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
    }

    @Transactional
    public void bootstrap() {
        if (!properties.isEnabled()) {
            return;
        }
        validateConfiguration();
        if (operatorRepository.count() > 0) {
            LOGGER.info("Administrator bootstrap skipped because operators already exist");
            return;
        }

        String username = OperatorNormalizer.normalizeUsername(properties.getUsername());
        String email = OperatorNormalizer.normalizeEmail(properties.getEmail());
        passwordPolicy.validate(properties.getPassword());
        String encodedPassword = passwordEncoder.encode(properties.getPassword());
        operatorRepository.save(
                Operator.create(username, email, encodedPassword, "Initial", "Administrator", OperatorRole.ADMIN));
        LOGGER.info("Administrator bootstrap completed");
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
}
