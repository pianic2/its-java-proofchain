package it.itsprodigi.proofchain.custodycase.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.custodycase.api.CreateCaseRequest;
import it.itsprodigi.proofchain.custodycase.api.PatchCaseMetadataRequest;
import it.itsprodigi.proofchain.custodycase.api.UpdateCaseStatusRequest;
import it.itsprodigi.proofchain.custodycase.domain.CaseMembership;
import it.itsprodigi.proofchain.custodycase.domain.CasePriority;
import it.itsprodigi.proofchain.custodycase.domain.CaseStatus;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.custodycase.persistence.CustodyCaseRepository;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class CustodyCaseServiceTest {

    private static final String BCRYPT_HASH = "$2a$10$01234567890123456789012345678901234567890123456789012";

    @Mock
    private CustodyCaseRepository custodyCases;

    @Mock
    private CaseMembershipRepository memberships;

    @Mock
    private OperatorRepository operators;

    @Mock
    private CaseAccessService access;

    private CustodyCaseService service;
    private Operator operator;
    private AuthenticatedOperator actor;

    @BeforeEach
    void setUp() {
        service = new CustodyCaseService(custodyCases, memberships, operators, access, new CaseMapper());
        operator = Operator.create(
                "manager", "manager@example.com", BCRYPT_HASH, "Case", "Manager", OperatorRole.CASE_MANAGER);
        actor = new AuthenticatedOperator(
                operator.getId(),
                operator.getUsername(),
                operator.getEmail(),
                operator.getFirstName(),
                operator.getLastName(),
                operator.getRole(),
                operator.getStatus(),
                operator.getCreatedAt(),
                operator.getUpdatedAt());
    }

    @Test
    void createPersistsCaseAndCreatorMembershipWithTheSameManagedOperator() {
        when(operators.findById(actor.id())).thenReturn(Optional.of(operator));
        CreateCaseRequest request =
                new CreateCaseRequest("  New case  ", " ", " Court ", null, " Rome ", CasePriority.HIGH);

        var response = service.create(request, actor);

        ArgumentCaptor<CustodyCase> caseCaptor = ArgumentCaptor.forClass(CustodyCase.class);
        ArgumentCaptor<CaseMembership> membershipCaptor = ArgumentCaptor.forClass(CaseMembership.class);
        verify(custodyCases).save(caseCaptor.capture());
        verify(memberships).saveAndFlush(membershipCaptor.capture());
        assertThat(response.title()).isEqualTo("New case");
        assertThat(response.description()).isNull();
        assertThat(response.status()).isEqualTo(CaseStatus.OPEN);
        assertThat(membershipCaptor.getValue().getCustodyCase()).isSameAs(caseCaptor.getValue());
        assertThat(membershipCaptor.getValue().getOperator()).isSameAs(operator);
        assertThat(membershipCaptor.getValue().getAssignedBy()).isSameAs(operator);
    }

    @Test
    void patchDistinguishesAbsentFieldsFromExplicitNullAndRejectsRequiredNull() throws Exception {
        CustodyCase custodyCase = caseEntity();
        when(access.requireMetadataModificationPermission(custodyCase.getId(), actor))
                .thenReturn(custodyCase);
        JsonMapper mapper = JsonMapper.builder().build();
        PatchCaseMetadataRequest patch =
                mapper.readValue("{\"description\":null,\"priority\":\"CRITICAL\"}", PatchCaseMetadataRequest.class);

        var response = service.updateMetadata(custodyCase.getId(), patch, actor);

        assertThat(response.title()).isEqualTo("Initial title");
        assertThat(response.description()).isNull();
        assertThat(response.authorityName()).isEqualTo("Authority");
        assertThat(response.priority()).isEqualTo(CasePriority.CRITICAL);
        verify(custodyCases).flush();

        PatchCaseMetadataRequest invalid = mapper.readValue("{\"title\":null}", PatchCaseMetadataRequest.class);
        assertThatThrownBy(() -> service.updateMetadata(custodyCase.getId(), invalid, actor))
                .isInstanceOf(CaseRequestValidationException.class);
    }

    @Test
    void closeIsIrreversibleAndAnIdenticalRepeatDoesNotFlushOrTouchTimestamps() {
        CustodyCase custodyCase = caseEntity();
        when(access.requireClosurePermission(custodyCase.getId(), actor)).thenReturn(custodyCase);

        var closed = service.updateStatus(custodyCase.getId(), new UpdateCaseStatusRequest(CaseStatus.CLOSED), actor);
        Instant updatedAt = closed.updatedAt();
        Instant closedAt = closed.closedAt();
        var repeated = service.updateStatus(custodyCase.getId(), new UpdateCaseStatusRequest(CaseStatus.CLOSED), actor);

        assertThat(repeated.updatedAt()).isEqualTo(updatedAt);
        assertThat(repeated.closedAt()).isEqualTo(closedAt);
        verify(custodyCases).flush();
    }

    @Test
    void closedMetadataAndOpenTargetProduceDedicatedConflicts() throws Exception {
        CustodyCase custodyCase = caseEntity();
        custodyCase.close();
        when(access.requireMetadataModificationPermission(custodyCase.getId(), actor))
                .thenReturn(custodyCase);
        when(access.requireClosurePermission(custodyCase.getId(), actor)).thenReturn(custodyCase);
        PatchCaseMetadataRequest patch =
                JsonMapper.builder().build().readValue("{\"title\":\"Changed title\"}", PatchCaseMetadataRequest.class);

        assertThatThrownBy(() -> service.updateMetadata(custodyCase.getId(), patch, actor))
                .isInstanceOf(CaseClosedException.class);
        assertThatThrownBy(() ->
                        service.updateStatus(custodyCase.getId(), new UpdateCaseStatusRequest(CaseStatus.OPEN), actor))
                .isInstanceOf(InvalidCaseStatusTransitionException.class);
        verify(custodyCases, never()).flush();
    }

    @Test
    void validatesPaginationAndRejectsEverySortOccurrence() {
        assertThatThrownBy(() -> service.list(-1, 20, List.of(), actor))
                .isInstanceOf(CaseRequestValidationException.class);
        assertThatThrownBy(() -> service.list(0, 0, List.of(), actor))
                .isInstanceOf(CaseRequestValidationException.class);
        assertThatThrownBy(() -> service.list(0, 101, List.of(), actor))
                .isInstanceOf(CaseRequestValidationException.class);
        assertThatThrownBy(() -> service.list(0, 20, List.of("createdAt,desc"), actor))
                .isInstanceOf(CaseRequestValidationException.class);
        verify(access, never())
                .findAccessibleCases(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private CustodyCase caseEntity() {
        return CustodyCase.create(
                "Initial title", "Description", "Authority", "REF", "Rome", CasePriority.MEDIUM, operator);
    }
}
