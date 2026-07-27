package it.itsprodigi.proofchain.operator.application;

import it.itsprodigi.proofchain.common.config.PasswordSecurityProperties;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class PasswordPolicy {

    private static final int MAX_BCRYPT_BYTES = 72;
    private final PasswordSecurityProperties properties;

    public PasswordPolicy(PasswordSecurityProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        properties.validate();
    }

    public void validate(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("password must not be blank");
        }
        int characters = password.codePointCount(0, password.length());
        if (characters < properties.getMinLength() || characters > properties.getMaxLength()) {
            throw new IllegalArgumentException("password length is outside the configured character limits");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BCRYPT_BYTES) {
            throw new IllegalArgumentException("password must not exceed 72 UTF-8 bytes");
        }
    }
}
