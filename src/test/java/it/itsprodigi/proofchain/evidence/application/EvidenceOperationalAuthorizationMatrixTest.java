package it.itsprodigi.proofchain.evidence.application;

import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.custodyCase;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.evidence;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.operator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.common.exception.ResourceNotFoundException;
import it.itsprodigi.proofchain.custodycase.application.CaseAccessService;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceRepository;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import jakarta.persistence.EntityManager;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * Frozen Sprint 5 authorization matrix. Every command is checked against every principal kind through the shared
 * operational access service, including the anti-enumeration outcome for a non-member.
 */
@ExtendWith(MockitoExtension.class)
class EvidenceOperationalAuthorizationMatrixTest {

    @Mock
    private DigitalEvidenceRepository evidences;

    @Mock
    private CaseAccessService caseAccess;

    @Mock
    private CaseMembershipRepository memberships;

    @Mock
    private OperatorRepository operators;

    @Mock
    private EntityManager entityManager;

    private enum Principal {
        ADMIN(OperatorRole.ADMIN, false, false),
        MEMBER_CASE_MANAGER(OperatorRole.CASE_MANAGER, true, false),
        MEMBER_EVIDENCE_OFFICER_HOLDER(OperatorRole.EVIDENCE_OFFICER, true, true),
        MEMBER_EVIDENCE_OFFICER_NON_HOLDER(OperatorRole.EVIDENCE_OFFICER, true, false),
        MEMBER_AUDITOR(OperatorRole.AUDITOR, true, false),
        NON_MEMBER(OperatorRole.CASE_MANAGER, false, false);

        private final OperatorRole role;
        private final boolean member;
        private final boolean holder;

        Principal(OperatorRole role, boolean member, boolean holder) {
            this.role = role;
            this.member = member;
            this.holder = holder;
        }
    }

    private enum Outcome {
        ALLOWED,
        FORBIDDEN,
        NOT_FOUND
    }

    static Stream<Arguments> matrix() {
        return Stream.of(
                        row(
                                EvidenceOperationalCommand.CUSTODY_TRANSFER,
                                Outcome.ALLOWED,
                                Outcome.ALLOWED,
                                Outcome.ALLOWED,
                                Outcome.FORBIDDEN,
                                Outcome.FORBIDDEN,
                                Outcome.NOT_FOUND),
                        row(
                                EvidenceOperationalCommand.METADATA_UPDATE,
                                Outcome.ALLOWED,
                                Outcome.ALLOWED,
                                Outcome.ALLOWED,
                                Outcome.ALLOWED,
                                Outcome.FORBIDDEN,
                                Outcome.NOT_FOUND),
                        row(
                                EvidenceOperationalCommand.INTEGRITY_VERIFICATION,
                                Outcome.ALLOWED,
                                Outcome.ALLOWED,
                                Outcome.ALLOWED,
                                Outcome.ALLOWED,
                                Outcome.ALLOWED,
                                Outcome.NOT_FOUND),
                        row(
                                EvidenceOperationalCommand.EVIDENCE_SEAL,
                                Outcome.ALLOWED,
                                Outcome.ALLOWED,
                                Outcome.ALLOWED,
                                Outcome.FORBIDDEN,
                                Outcome.FORBIDDEN,
                                Outcome.NOT_FOUND),
                        row(
                                EvidenceOperationalCommand.EVIDENCE_RELEASE,
                                Outcome.ALLOWED,
                                Outcome.ALLOWED,
                                Outcome.FORBIDDEN,
                                Outcome.FORBIDDEN,
                                Outcome.FORBIDDEN,
                                Outcome.NOT_FOUND))
                .flatMap(row -> row);
    }

    private static Stream<Arguments> row(EvidenceOperationalCommand command, Outcome... outcomes) {
        Principal[] principals = Principal.values();
        return IntStream.range(0, principals.length)
                .mapToObj(index -> Arguments.of(command, principals[index], outcomes[index]));
    }

    @ParameterizedTest(name = "{0} for {1} is {2}")
    @MethodSource("matrix")
    void frozenMatrixIsEnforced(EvidenceOperationalCommand command, Principal principal, Outcome expected) {
        ThrowingCallable authorization = authorizationFor(command, principal);
        switch (expected) {
            case ALLOWED -> assertThatCode(authorization).doesNotThrowAnyException();
            case FORBIDDEN -> assertThatThrownBy(authorization).isInstanceOf(AccessDeniedException.class);
            case NOT_FOUND -> assertThatThrownBy(authorization).isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Test
    void hiddenEvidenceAndNonexistentEvidenceAreIndistinguishable() {
        Operator actor = operator("outsider", OperatorRole.CASE_MANAGER);
        AuthenticatedOperator principal = principal(actor);
        UUID hiddenEvidenceId = UUID.randomUUID();
        UUID missingEvidenceId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        EvidenceOperationalAccessService service = service();
        lenient().when(evidences.findCaseIdById(hiddenEvidenceId)).thenReturn(Optional.of(caseId));
        lenient().when(evidences.findCaseIdById(missingEvidenceId)).thenReturn(Optional.empty());
        doThrow(new ResourceNotFoundException()).when(caseAccess).requireVisibleCase(caseId, principal);

        ResourceNotFoundException hidden =
                catchNotFound(() -> service.requireVisibleCaseId(hiddenEvidenceId, principal));
        ResourceNotFoundException missing =
                catchNotFound(() -> service.requireVisibleCaseId(missingEvidenceId, principal));

        assertThat(hidden).hasSameClassAs(missing).hasMessage(missing.getMessage());
    }

    @Test
    void suspendedButVisibleMemberIsForbiddenInsteadOfHidden() {
        Operator actor = operator("suspended", OperatorRole.CASE_MANAGER);
        actor.changeStatus(OperatorStatus.SUSPENDED);
        AuthenticatedOperator principal = principal(actor);
        UUID caseId = UUID.randomUUID();
        EvidenceOperationalAccessService service = service();
        lenient().when(operators.findById(actor.getId())).thenReturn(Optional.of(actor));
        lenient()
                .when(memberships.existsByCustodyCaseIdAndOperatorId(caseId, actor.getId()))
                .thenReturn(true);

        assertThatThrownBy(() ->
                        service.requireAuthorizedActor(EvidenceOperationalCommand.METADATA_UPDATE, caseId, principal))
                .isInstanceOf(AccessDeniedException.class);
    }

    private ThrowingCallable authorizationFor(EvidenceOperationalCommand command, Principal principal) {
        EvidenceOperationalAccessService service = service();
        UUID caseId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        Operator actor = operator("matrix-" + principal.name().toLowerCase(Locale.ROOT), principal.role);
        Operator holder = principal.holder ? actor : operator("other-holder", OperatorRole.EVIDENCE_OFFICER);
        AuthenticatedOperator authenticated = principal(actor);
        CustodyCase owningCase = custodyCase("Matrix case", actor);
        DigitalEvidence target = evidence(owningCase, holder, "MATRIX");

        lenient().when(evidences.findCaseIdById(evidenceId)).thenReturn(Optional.of(caseId));
        lenient().when(operators.findById(actor.getId())).thenReturn(Optional.of(actor));
        lenient()
                .when(memberships.existsByCustodyCaseIdAndOperatorId(caseId, actor.getId()))
                .thenReturn(principal.member);
        if (principal == Principal.NON_MEMBER) {
            lenient()
                    .doThrow(new ResourceNotFoundException())
                    .when(caseAccess)
                    .requireVisibleCase(caseId, authenticated);
        }

        return () -> {
            UUID resolvedCaseId = service.requireVisibleCaseId(evidenceId, authenticated);
            Operator current = service.requireAuthorizedActor(command, resolvedCaseId, authenticated);
            service.requireAuthorizedHolder(command, current, target);
        };
    }

    private EvidenceOperationalAccessService service() {
        return new EvidenceOperationalAccessService(evidences, caseAccess, memberships, operators, entityManager);
    }

    private static ResourceNotFoundException catchNotFound(Runnable action) {
        try {
            action.run();
        } catch (ResourceNotFoundException exception) {
            return exception;
        }
        throw new AssertionError("Expected a ResourceNotFoundException");
    }

    private static AuthenticatedOperator principal(Operator operator) {
        return new AuthenticatedOperator(
                operator.getId(),
                operator.getUsername(),
                operator.getEmail(),
                operator.getFirstName(),
                operator.getLastName(),
                operator.getRole(),
                OperatorStatus.ACTIVE,
                operator.getCreatedAt(),
                operator.getUpdatedAt());
    }
}
