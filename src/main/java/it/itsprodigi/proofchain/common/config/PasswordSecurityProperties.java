package it.itsprodigi.proofchain.common.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Password policy and hashing strength.
 *
 * <p>Every constraint is enforced while the properties are bound, so an invalid password policy or an out-of-range
 * BCrypt strength fails the application context instead of silently weakening authentication.
 */
@Validated
@ConfigurationProperties(prefix = "proofchain.password")
public class PasswordSecurityProperties {

    @Min(value = 1, message = "proofchain.password.min-length must be positive")
    private int minLength = 12;

    @Min(value = 1, message = "proofchain.password.max-length must be positive")
    private int maxLength = 128;

    @Min(value = 4, message = "proofchain.password.bcrypt-strength must be between 4 and 31")
    @Max(value = 31, message = "proofchain.password.bcrypt-strength must be between 4 and 31")
    private int bcryptStrength = 12;

    public int getMinLength() {
        return minLength;
    }

    public void setMinLength(int minLength) {
        this.minLength = minLength;
    }

    public int getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(int maxLength) {
        this.maxLength = maxLength;
    }

    public int getBcryptStrength() {
        return bcryptStrength;
    }

    public void setBcryptStrength(int bcryptStrength) {
        this.bcryptStrength = bcryptStrength;
    }

    @AssertTrue(message = "proofchain.password.min-length must not exceed proofchain.password.max-length")
    public boolean isLengthRangeOrdered() {
        return minLength <= maxLength;
    }

    public void validate() {
        if (minLength > maxLength) {
            throw new IllegalArgumentException("proofchain.password.min-length must not exceed max-length");
        }
        if (bcryptStrength < 4 || bcryptStrength > 31) {
            throw new IllegalArgumentException("proofchain.password.bcrypt-strength must be between 4 and 31");
        }
    }
}
