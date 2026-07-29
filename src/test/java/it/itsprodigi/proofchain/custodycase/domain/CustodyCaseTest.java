package it.itsprodigi.proofchain.custodycase.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CustodyCaseTest {

    private static final String BCRYPT_HASH = "$2a$10$01234567890123456789012345678901234567890123456789012";

    @Test
    void createsAnOpenCaseWithNormalizedMetadataAndMicrosecondTimestamps() {
        Operator creator = operator("creator");

        CustodyCase custodyCase = CustodyCase.create(
                "\u2003Custody Cäsë\u2003",
                "\u2003Preserves Unicode\u2003",
                "\u2003Court of Rome\u2003",
                "\u2003REF-42\u2003",
                "\u2003Rome\u2003",
                CasePriority.HIGH,
                creator);

        assertThat(custodyCase.getId().version()).isEqualTo(4);
        assertThat(custodyCase.getTitle()).isEqualTo("Custody Cäsë");
        assertThat(custodyCase.getDescription()).isEqualTo("Preserves Unicode");
        assertThat(custodyCase.getAuthorityName()).isEqualTo("Court of Rome");
        assertThat(custodyCase.getExternalReference()).isEqualTo("REF-42");
        assertThat(custodyCase.getLocation()).isEqualTo("Rome");
        assertThat(custodyCase.getPriority()).isEqualTo(CasePriority.HIGH);
        assertThat(custodyCase.getStatus()).isEqualTo(CaseStatus.OPEN);
        assertThat(custodyCase.getCreatedBy()).isSameAs(creator);
        assertThat(custodyCase.getCreatedAt()).isEqualTo(custodyCase.getUpdatedAt());
        assertThat(custodyCase.getCreatedAt())
                .isEqualTo(custodyCase.getCreatedAt().truncatedTo(ChronoUnit.MICROS));
        assertThat(custodyCase.getClosedAt()).isNull();
        assertThat(custodyCase.getVersion()).isZero();
    }

    @Test
    void normalizesOptionalBlankMetadataToNull() {
        CustodyCase custodyCase = custodyCase("\u2003Case title\u2003", "\u2003", "\t", "\n", "  ");

        assertThat(custodyCase.getTitle()).isEqualTo("Case title");
        assertThat(custodyCase.getDescription()).isNull();
        assertThat(custodyCase.getAuthorityName()).isNull();
        assertThat(custodyCase.getExternalReference()).isNull();
        assertThat(custodyCase.getLocation()).isNull();
    }

    @Test
    void exposesOnlyFrozenPrioritiesAndStatuses() {
        assertThat(Set.of(CasePriority.values()))
                .containsExactlyInAnyOrder(
                        CasePriority.LOW, CasePriority.MEDIUM, CasePriority.HIGH, CasePriority.CRITICAL);
        assertThat(Set.of(CaseStatus.values())).containsExactlyInAnyOrder(CaseStatus.OPEN, CaseStatus.CLOSED);
    }

    @Test
    void rejectsInvalidRequiredAndOptionalMetadata() {
        assertThatThrownBy(() -> custodyCase("  ", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> custodyCase("ab", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> custodyCase("a".repeat(201), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> custodyCase("valid", "a".repeat(2001), null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> custodyCase("valid", null, "a".repeat(201), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> custodyCase("valid", null, null, "a".repeat(201), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> custodyCase("valid", null, null, null, "a".repeat(301)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustodyCase.create("valid", null, null, null, null, null, operator("creator")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CustodyCase.create("valid", null, null, null, null, CasePriority.LOW, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void updatesMetadataAtMicrosecondPrecisionWhileTheCaseIsOpen() {
        CustodyCase custodyCase = custodyCase("Initial case", null, null, null, null);
        Instant previousUpdatedAt = custodyCase.getUpdatedAt();

        custodyCase.updateMetadata(
                "Updated case", " Description ", "Authority", "Reference", "Location", CasePriority.CRITICAL);

        assertThat(custodyCase.getTitle()).isEqualTo("Updated case");
        assertThat(custodyCase.getPriority()).isEqualTo(CasePriority.CRITICAL);
        assertThat(custodyCase.getUpdatedAt()).isAfter(previousUpdatedAt);
        assertThat(custodyCase.getUpdatedAt())
                .isEqualTo(custodyCase.getUpdatedAt().truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void closesIrreversiblyAndRejectsFurtherMetadataChanges() {
        CustodyCase custodyCase = custodyCase("Initial case", null, null, null, null);

        custodyCase.close();

        assertThat(custodyCase.getStatus()).isEqualTo(CaseStatus.CLOSED);
        assertThat(custodyCase.getClosedAt()).isEqualTo(custodyCase.getUpdatedAt());
        assertThat(custodyCase.getClosedAt())
                .isEqualTo(custodyCase.getClosedAt().truncatedTo(ChronoUnit.MICROS));
        assertThatThrownBy(custodyCase::close).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> custodyCase.updateMetadata("Updated", null, null, null, null, CasePriority.LOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void createsMembershipWithUuidV4AndMicrosecondTimestamp() {
        CustodyCase custodyCase = custodyCase("Initial case", null, null, null, null);
        Operator member = operator("member");

        CaseMembership membership = CaseMembership.assign(custodyCase, member, custodyCase.getCreatedBy());

        assertThat(membership.getId().version()).isEqualTo(4);
        assertThat(membership.getCustodyCase()).isSameAs(custodyCase);
        assertThat(membership.getOperator()).isSameAs(member);
        assertThat(membership.getAssignedBy()).isSameAs(custodyCase.getCreatedBy());
        assertThat(membership.getAssignedAt())
                .isEqualTo(membership.getAssignedAt().truncatedTo(ChronoUnit.MICROS));
    }

    private static CustodyCase custodyCase(
            String title, String description, String authorityName, String externalReference, String location) {
        return CustodyCase.create(
                title,
                description,
                authorityName,
                externalReference,
                location,
                CasePriority.MEDIUM,
                operator("creator"));
    }

    private static Operator operator(String username) {
        return Operator.create(
                username, username + "@example.com", BCRYPT_HASH, "Jane", "Doe", OperatorRole.CASE_MANAGER);
    }
}
