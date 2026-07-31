package it.itsprodigi.proofchain.evidence.application;

import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Frozen Sprint 5 operational command catalogue.
 *
 * <p>Each constant carries the authorization data every operational workflow reuses. {@code ADMIN} is always allowed
 * globally and is therefore never part of {@link #memberRoles()}: the remaining roles are only allowed when the actor
 * is a member of the owning custody case.
 */
public enum EvidenceOperationalCommand {
    CUSTODY_TRANSFER(
            "custody-transfer", EnumSet.of(OperatorRole.CASE_MANAGER, OperatorRole.EVIDENCE_OFFICER), true, true, true),
    METADATA_UPDATE(
            "metadata-update", EnumSet.of(OperatorRole.CASE_MANAGER, OperatorRole.EVIDENCE_OFFICER), false, true, true),
    INTEGRITY_VERIFICATION(
            "integrity-verification",
            EnumSet.of(OperatorRole.CASE_MANAGER, OperatorRole.EVIDENCE_OFFICER, OperatorRole.AUDITOR),
            false,
            false,
            false),
    EVIDENCE_SEAL(
            "evidence-seal", EnumSet.of(OperatorRole.CASE_MANAGER, OperatorRole.EVIDENCE_OFFICER), true, true, true),
    EVIDENCE_RELEASE("evidence-release", EnumSet.of(OperatorRole.CASE_MANAGER), false, true, true);

    private final String commandName;
    private final Set<OperatorRole> memberRoles;
    private final boolean evidenceOfficerMustBeCurrentHolder;
    private final boolean mutating;
    private final boolean reasonRequired;

    EvidenceOperationalCommand(
            String commandName,
            EnumSet<OperatorRole> memberRoles,
            boolean evidenceOfficerMustBeCurrentHolder,
            boolean mutating,
            boolean reasonRequired) {
        this.commandName = commandName;
        this.memberRoles = Collections.unmodifiableSet(EnumSet.copyOf(memberRoles));
        this.evidenceOfficerMustBeCurrentHolder = evidenceOfficerMustBeCurrentHolder;
        this.mutating = mutating;
        this.reasonRequired = reasonRequired;
    }

    /** Stable sanitized identifier used in operational logs. */
    public String commandName() {
        return commandName;
    }

    /** Case-member roles allowed to run the command, excluding the global {@code ADMIN} allowance. */
    public Set<OperatorRole> memberRoles() {
        return memberRoles;
    }

    /** True when a member {@code EVIDENCE_OFFICER} may only run the command on evidence it currently holds. */
    public boolean evidenceOfficerMustBeCurrentHolder() {
        return evidenceOfficerMustBeCurrentHolder;
    }

    /** True when the command changes evidence metadata, holder or lifecycle. */
    public boolean mutating() {
        return mutating;
    }

    /** True when the command carries an operational reason. */
    public boolean reasonRequired() {
        return reasonRequired;
    }

    /** Role gate evaluated before the current-holder gate. */
    public boolean allowsRole(OperatorRole role) {
        Objects.requireNonNull(role, "role must not be null");
        return role == OperatorRole.ADMIN || memberRoles.contains(role);
    }

    /** Complete matrix decision for an operator that is already known to see the owning custody case. */
    public boolean allows(OperatorRole role, boolean caseMember, boolean currentHolder) {
        Objects.requireNonNull(role, "role must not be null");
        if (role == OperatorRole.ADMIN) {
            return true;
        }
        if (!caseMember || !memberRoles.contains(role)) {
            return false;
        }
        return !evidenceOfficerMustBeCurrentHolder || role != OperatorRole.EVIDENCE_OFFICER || currentHolder;
    }
}
