package it.itsprodigi.proofchain.custodycase.domain;

import it.itsprodigi.proofchain.operator.domain.Operator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "case_memberships",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_case_memberships_case_operator",
                        columnNames = {"case_id", "operator_id"}))
public class CaseMembership {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "case_id", nullable = false, updatable = false)
    private CustodyCase custodyCase;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operator_id", nullable = false, updatable = false)
    private Operator operator;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assigned_by_operator_id", nullable = false, updatable = false)
    private Operator assignedBy;

    @NotNull
    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    protected CaseMembership() {}

    private CaseMembership(CustodyCase custodyCase, Operator operator, Operator assignedBy) {
        id = UUID.randomUUID();
        this.custodyCase = Objects.requireNonNull(custodyCase, "custodyCase must not be null");
        this.operator = Objects.requireNonNull(operator, "operator must not be null");
        this.assignedBy = Objects.requireNonNull(assignedBy, "assignedBy must not be null");
        assignedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public static CaseMembership assign(CustodyCase custodyCase, Operator operator, Operator assignedBy) {
        return new CaseMembership(custodyCase, operator, assignedBy);
    }

    public UUID getId() {
        return id;
    }

    public CustodyCase getCustodyCase() {
        return custodyCase;
    }

    public Operator getOperator() {
        return operator;
    }

    public Operator getAssignedBy() {
        return assignedBy;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CaseMembership membership)) {
            return false;
        }
        return id != null && id.equals(membership.id);
    }

    @Override
    public int hashCode() {
        return CaseMembership.class.hashCode();
    }

    @Override
    public String toString() {
        return "CaseMembership{" + "id=" + id + '}';
    }
}
