package it.itsprodigi.proofchain.operator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "operators")
public class Operator {

    private static final String USERNAME_PATTERN = "[a-z0-9._-]+";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @NotBlank
    @Size(min = 3, max = 64)
    @Pattern(regexp = USERNAME_PATTERN)
    @Column(name = "username", nullable = false, length = 64)
    private String username;

    @NotBlank
    @Size(max = 320)
    @Email
    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @NotBlank
    @Size(min = 60, max = 60)
    @Column(name = "password_hash", nullable = false, length = 60)
    private String passwordHash;

    @NotBlank
    @Size(max = 100)
    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @NotBlank
    @Size(max = 100)
    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private OperatorRole role;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OperatorStatus status;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @NotNull
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @PositiveOrZero
    @Column(name = "version", nullable = false)
    private long version;

    protected Operator() {}

    private Operator(
            String username, String email, String passwordHash, String firstName, String lastName, OperatorRole role) {
        id = UUID.randomUUID();
        this.username = validUsername(username);
        this.email = validEmail(email);
        this.passwordHash = validPasswordHash(passwordHash);
        this.firstName = validName(firstName, "firstName");
        this.lastName = validName(lastName, "lastName");
        this.role = Objects.requireNonNull(role, "role must not be null");
        status = OperatorStatus.ACTIVE;
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        version = 0L;
    }

    public static Operator create(
            String username, String email, String passwordHash, String firstName, String lastName, OperatorRole role) {
        return new Operator(username, email, passwordHash, firstName, lastName, role);
    }

    public void changeUsername(String username) {
        this.username = validUsername(username);
        touch();
    }

    public void changeEmail(String email) {
        this.email = validEmail(email);
        touch();
    }

    public void changePasswordHash(String passwordHash) {
        this.passwordHash = validPasswordHash(passwordHash);
        touch();
    }

    public void changeName(String firstName, String lastName) {
        String newFirstName = validName(firstName, "firstName");
        String newLastName = validName(lastName, "lastName");
        this.firstName = newFirstName;
        this.lastName = newLastName;
        touch();
    }

    public void changeRole(OperatorRole role) {
        this.role = Objects.requireNonNull(role, "role must not be null");
        touch();
    }

    public void changeStatus(OperatorStatus status) {
        this.status = Objects.requireNonNull(status, "status must not be null");
        touch();
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public OperatorRole getRole() {
        return role;
    }

    public OperatorStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Operator operator)) {
            return false;
        }
        return id != null && id.equals(operator.id);
    }

    @Override
    public int hashCode() {
        return Operator.class.hashCode();
    }

    @Override
    public String toString() {
        return "Operator{" + "id=" + id + ", username='" + username + '\'' + ", role=" + role + ", status=" + status
                + '}';
    }

    private static String validUsername(String username) {
        String normalized = OperatorNormalizer.normalizeUsername(username);
        if (normalized.length() < 3 || normalized.length() > 64 || !normalized.matches(USERNAME_PATTERN)) {
            throw new IllegalArgumentException(
                    "username must be 3 to 64 lowercase letters, digits, dots, underscores or hyphens");
        }
        return normalized;
    }

    private static String validEmail(String email) {
        String normalized = OperatorNormalizer.normalizeEmail(email);
        requireNotBlank(normalized, "email");
        if (normalized.length() > 320) {
            throw new IllegalArgumentException("email must not exceed 320 characters");
        }
        return normalized;
    }

    private static String validPasswordHash(String passwordHash) {
        requireNotBlank(passwordHash, "passwordHash");
        if (passwordHash.length() != 60) {
            throw new IllegalArgumentException("passwordHash must be a 60-character BCrypt value");
        }
        return passwordHash;
    }

    private static String validName(String name, String fieldName) {
        String normalized = OperatorNormalizer.normalizeName(name, fieldName);
        requireNotBlank(normalized, fieldName);
        if (normalized.length() > 100) {
            throw new IllegalArgumentException(fieldName + " must not exceed 100 characters");
        }
        return normalized;
    }

    private static void requireNotBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    private void touch() {
        Instant now = Instant.now();
        updatedAt = now.isAfter(updatedAt) ? now : updatedAt.plusNanos(1);
    }
}
