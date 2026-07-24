package it.itsprodigi.proofchain.operator.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OperatorTest {

    private static final String BCRYPT_HASH = "$2a$10$01234567890123456789012345678901234567890123456789012";

    @Test
    void createsCanonicalOperatorIdentity() {
        Operator operator = operator("  Jane.Doe  ", "  Jane.Doe@Example.COM  ", "  Jane  ", "  DOE  ");

        assertThat(operator.getId().version()).isEqualTo(4);
        assertThat(operator.getUsername()).isEqualTo("jane.doe");
        assertThat(operator.getEmail()).isEqualTo("jane.doe@example.com");
        assertThat(operator.getFirstName()).isEqualTo("Jane");
        assertThat(operator.getLastName()).isEqualTo("DOE");
        assertThat(operator.getCreatedAt()).isEqualTo(operator.getUpdatedAt());
        assertThat(operator.getVersion()).isZero();
        assertThat(operator.getStatus()).isEqualTo(OperatorStatus.ACTIVE);
    }

    @Test
    void exposesOnlyFrozenRolesAndStatuses() {
        assertThat(Set.of(OperatorRole.values()))
                .containsExactlyInAnyOrder(
                        OperatorRole.ADMIN,
                        OperatorRole.CASE_MANAGER,
                        OperatorRole.EVIDENCE_OFFICER,
                        OperatorRole.AUDITOR);
        assertThat(Set.of(OperatorStatus.values()))
                .containsExactlyInAnyOrder(OperatorStatus.ACTIVE, OperatorStatus.SUSPENDED, OperatorStatus.DISABLED);
    }

    @Test
    void rejectsInvalidUsernamePatternAndLength() {
        assertThatThrownBy(() -> operator("ab", "valid@example.com", "Jane", "Doe"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> operator("a".repeat(65), "valid@example.com", "Jane", "Doe"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> operator("not allowed", "valid@example.com", "Jane", "Doe"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidFieldLengths() {
        assertThatThrownBy(() -> operator("valid", "a".repeat(309) + "@example.com", "Jane", "Doe"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> operator("valid", "valid@example.com", "a".repeat(101), "Doe"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> operator("valid", "valid@example.com", "Jane", "a".repeat(101)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> Operator.create("valid", "valid@example.com", "short", "Jane", "Doe", OperatorRole.ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void beanValidationRejectsInvalidEmailSyntax() {
        Operator operator = operator("valid", "not-an-email", "Jane", "Doe");

        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            assertThat(validatorFactory.getValidator().validate(operator))
                    .anyMatch(
                            violation -> violation.getPropertyPath().toString().equals("email"));
        }
    }

    @Test
    void rejectsNullAndBlankAggregateState() {
        assertThatThrownBy(() -> operator(null, "valid@example.com", "Jane", "Doe"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> operator("valid", " ", "Jane", "Doe")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> operator("valid", "valid@example.com", " ", "Doe"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> operator("valid", "valid@example.com", "Jane", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Operator.create("valid", "valid@example.com", BCRYPT_HASH, "Jane", "Doe", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void everyDomainUpdateRefreshesUpdatedAt() {
        Operator operator = operator("valid", "valid@example.com", "Jane", "Doe");

        Instant previous = operator.getUpdatedAt();
        operator.changeUsername("changed");
        assertThat(operator.getUpdatedAt()).isAfter(previous);
        previous = operator.getUpdatedAt();
        operator.changeEmail("changed@example.com");
        assertThat(operator.getUpdatedAt()).isAfter(previous);
        previous = operator.getUpdatedAt();
        operator.changePasswordHash("$2b$12$" + "x".repeat(53));
        assertThat(operator.getUpdatedAt()).isAfter(previous);
        previous = operator.getUpdatedAt();
        operator.changeName("Janet", "Smith");
        assertThat(operator.getUpdatedAt()).isAfter(previous);
        previous = operator.getUpdatedAt();
        operator.changeRole(OperatorRole.AUDITOR);
        assertThat(operator.getUpdatedAt()).isAfter(previous);
        previous = operator.getUpdatedAt();
        operator.changeStatus(OperatorStatus.SUSPENDED);
        assertThat(operator.getUpdatedAt()).isAfter(previous);
    }

    @Test
    void stringRepresentationNeverContainsPasswordHash() {
        Operator operator = operator("valid", "valid@example.com", "Jane", "Doe");

        assertThat(operator.toString()).doesNotContain(BCRYPT_HASH).doesNotContain("passwordHash");
    }

    private static Operator operator(String username, String email, String firstName, String lastName) {
        return Operator.create(username, email, BCRYPT_HASH, firstName, lastName, OperatorRole.ADMIN);
    }
}
