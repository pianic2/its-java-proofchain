package it.itsprodigi.proofchain.operator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.itsprodigi.proofchain.common.config.PasswordSecurityProperties;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PasswordPolicyTest {

    @Test
    void usesTheProductionDefaults() {
        PasswordSecurityProperties properties = new PasswordSecurityProperties();

        assertThat(properties.getMinLength()).isEqualTo(12);
        assertThat(properties.getMaxLength()).isEqualTo(128);
        assertThat(properties.getBcryptStrength()).isEqualTo(12);
    }

    @Test
    void acceptsMinimumLengthAndRejectsConfiguredBoundaries() {
        PasswordSecurityProperties properties = properties(4, 8);
        PasswordPolicy policy = new PasswordPolicy(properties);

        policy.validate("p".repeat(4));
        assertThatThrownBy(() -> policy.validate("p".repeat(3))).hasMessageContaining("character limits");
        assertThatThrownBy(() -> policy.validate("p".repeat(9))).hasMessageContaining("character limits");
    }

    @Test
    void rejectsBlankAndPasswordsOverTheBcryptUtf8Boundary() {
        PasswordPolicy policy = new PasswordPolicy(properties(1, 128));

        assertThatThrownBy(() -> policy.validate(" ")).hasMessageContaining("blank");
        String tooManyBytes = "€".repeat(25);
        assertThat(tooManyBytes.getBytes(StandardCharsets.UTF_8)).hasSize(75);
        assertThatThrownBy(() -> policy.validate(tooManyBytes)).hasMessageContaining("72 UTF-8 bytes");
    }

    @Test
    void countsUnicodeCodePointsAndEncodesWithDifferentSalts() {
        PasswordSecurityProperties properties = properties(4, 8);
        PasswordPolicy policy = new PasswordPolicy(properties);
        String password = "😀".repeat(4);
        policy.validate(password);

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        String first = encoder.encode(password);
        String second = encoder.encode(password);
        assertThat(first).isNotEqualTo(second);
        assertThat(encoder.matches(password, first)).isTrue();
        assertThat(encoder.matches("q".repeat(4), first)).isFalse();
    }

    @Test
    void rejectsInvalidStrengthAndInvertedLimits() {
        PasswordSecurityProperties strength = properties(4, 8);
        strength.setBcryptStrength(32);
        assertThatThrownBy(strength::validate).hasMessageContaining("between 4 and 31");

        PasswordSecurityProperties limits = properties(8, 4);
        assertThatThrownBy(limits::validate).hasMessageContaining("min-length");
    }

    private static PasswordSecurityProperties properties(int min, int max) {
        PasswordSecurityProperties properties = new PasswordSecurityProperties();
        properties.setMinLength(min);
        properties.setMaxLength(max);
        properties.setBcryptStrength(4);
        return properties;
    }
}
