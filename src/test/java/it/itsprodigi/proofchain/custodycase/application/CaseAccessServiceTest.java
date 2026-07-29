package it.itsprodigi.proofchain.custodycase.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.common.exception.ResourceNotFoundException;
import it.itsprodigi.proofchain.custodycase.domain.CasePriority;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.custodycase.persistence.CustodyCaseRepository;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class CaseAccessServiceTest {

    private static final String BCRYPT_HASH = "$2a$10$01234567890123456789012345678901234567890123456789012";

    @Mock
    private CustodyCaseRepository custodyCases;

    @Mock
    private CaseMembershipRepository memberships;

    private CaseAccessService service;
    private UUID caseId;
    private CustodyCase custodyCase;

    @BeforeEach
    void setUp() {
        service = new CaseAccessService(custodyCases, memberships);
        caseId = UUID.randomUUID();
        custodyCase = CustodyCase.create("Case title", null, null, null, null, CasePriority.MEDIUM, operator());
    }

    @Test
    void adminReadsGloballyWithoutMembershipLookup() {
        AuthenticatedOperator admin = actor(OperatorRole.ADMIN);
        when(custodyCases.findByIdWithCreatedBy(caseId)).thenReturn(Optional.of(custodyCase));

        assertThat(service.requireReadableCase(caseId, admin)).isSameAs(custodyCase);

        verify(memberships, never()).existsByCustodyCaseIdAndOperatorId(caseId, admin.id());
    }

    @Test
    void memberReadsAndNonMemberGetsTheSameNotFoundContractAsMissingCase() {
        AuthenticatedOperator member = actor(OperatorRole.AUDITOR);
        when(memberships.existsByCustodyCaseIdAndOperatorId(caseId, member.id()))
                .thenReturn(true);
        when(custodyCases.findByIdWithCreatedBy(caseId)).thenReturn(Optional.of(custodyCase));
        assertThat(service.requireReadableCase(caseId, member)).isSameAs(custodyCase);

        UUID hiddenId = UUID.randomUUID();
        when(memberships.existsByCustodyCaseIdAndOperatorId(hiddenId, member.id()))
                .thenReturn(false);
        assertThatThrownBy(() -> service.requireReadableCase(hiddenId, member))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(custodyCases, never()).findByIdWithCreatedBy(hiddenId);
    }

    @Test
    void visibleReadOnlyMemberGetsAccessDeniedWhileCaseManagerMemberCanMutate() {
        AuthenticatedOperator auditor = actor(OperatorRole.AUDITOR);
        when(memberships.existsByCustodyCaseIdAndOperatorId(caseId, auditor.id()))
                .thenReturn(true);
        when(custodyCases.findByIdWithCreatedBy(caseId)).thenReturn(Optional.of(custodyCase));
        assertThatThrownBy(() -> service.requireMetadataModificationPermission(caseId, auditor))
                .isInstanceOf(AccessDeniedException.class);

        AuthenticatedOperator manager = actor(OperatorRole.CASE_MANAGER);
        when(memberships.existsByCustodyCaseIdAndOperatorId(caseId, manager.id()))
                .thenReturn(true);
        assertThat(service.requireClosurePermission(caseId, manager)).isSameAs(custodyCase);
        assertThat(service.requireMembershipManagementPermission(caseId, manager))
                .isSameAs(custodyCase);
    }

    @Test
    void accessiblePageSelectionIsCentralizedForAdminAndMember() {
        var pageable = PageRequest.of(0, 20);
        AuthenticatedOperator admin = actor(OperatorRole.ADMIN);
        AuthenticatedOperator member = actor(OperatorRole.EVIDENCE_OFFICER);
        var page = new PageImpl<>(java.util.List.of(custodyCase), pageable, 1);
        when(custodyCases.findPageForAdmin(pageable)).thenReturn(page);
        when(custodyCases.findPageForMember(member.id(), pageable)).thenReturn(page);

        assertThat(service.findAccessibleCases(pageable, admin)).isSameAs(page);
        assertThat(service.findAccessibleCases(pageable, member)).isSameAs(page);
    }

    private static AuthenticatedOperator actor(OperatorRole role) {
        return new AuthenticatedOperator(
                UUID.randomUUID(),
                "actor",
                "actor@example.com",
                "Actor",
                "One",
                role,
                OperatorStatus.ACTIVE,
                Instant.EPOCH,
                Instant.EPOCH);
    }

    private static Operator operator() {
        return Operator.create(
                "creator", "creator@example.com", BCRYPT_HASH, "Case", "Creator", OperatorRole.CASE_MANAGER);
    }
}
