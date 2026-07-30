package it.itsprodigi.proofchain.custodyevent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.common.exception.ResourceNotFoundException;
import it.itsprodigi.proofchain.custodycase.application.CaseAccessService;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodyevent.api.CustodyEventPageResponse;
import it.itsprodigi.proofchain.custodyevent.domain.CustodyEvent;
import it.itsprodigi.proofchain.custodyevent.persistence.CustodyEventRepository;
import it.itsprodigi.proofchain.evidence.application.EvidenceRequestValidationException;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceRepository;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class CustodyEventQueryServiceTest {

    private static final UUID CASE_ID = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final UUID EVIDENCE_ID = UUID.fromString("80000000-0000-4000-8000-000000000001");
    private static final UUID EVENT_ID = UUID.fromString("90000000-0000-4000-8000-000000000001");

    @Mock
    private DigitalEvidenceRepository evidences;

    @Mock
    private CustodyEventRepository events;

    @Mock
    private CaseAccessService access;

    @Mock
    private CustodyEventMapper mapper;

    @Mock
    private DigitalEvidence evidence;

    @Mock
    private CustodyCase custodyCase;

    private CustodyEventQueryService service;
    private AuthenticatedOperator actor;

    @BeforeEach
    void setUp() {
        service = new CustodyEventQueryService(evidences, events, access, mapper);
        actor = new AuthenticatedOperator(
                UUID.randomUUID(),
                "auditor",
                "auditor@example.test",
                "Audit",
                "Operator",
                OperatorRole.AUDITOR,
                OperatorStatus.ACTIVE,
                Instant.EPOCH,
                Instant.EPOCH);
    }

    @Test
    void resolvesEvidenceVisibilityBeforeDelegatingToTheUnsortedBoundedTimelinePage() {
        CustodyEvent event = org.mockito.Mockito.mock(CustodyEvent.class);
        PageRequest request = PageRequest.of(1, 200);
        var resultPage = new PageImpl<>(List.of(event), request, 201);
        var response = new CustodyEventPageResponse(List.of(), 1, 200, 201, 2);
        visibleEvidence();
        when(events.findAllByEvidenceIdOrderBySequenceNumberAsc(EVIDENCE_ID, request))
                .thenReturn(resultPage);
        when(mapper.toPage(resultPage)).thenReturn(response);

        assertThat(service.list(EVIDENCE_ID, 1, 200, null, actor)).isSameAs(response);

        InOrder order = inOrder(evidences, access, events, mapper);
        order.verify(evidences).findByIdForVisibility(EVIDENCE_ID);
        order.verify(access).requireReadableCase(CASE_ID, actor);
        order.verify(events).findAllByEvidenceIdOrderBySequenceNumberAsc(EVIDENCE_ID, request);
        order.verify(mapper).toPage(resultPage);
        assertThat(request.getSort().isUnsorted()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("invalidPages")
    void rejectsInvalidPaginationAndEveryRequestedSortBeforeVisibility(
            int page, int size, List<String> sortParameters) {
        assertThatThrownBy(() -> service.list(EVIDENCE_ID, page, size, sortParameters, actor))
                .isInstanceOf(EvidenceRequestValidationException.class);

        verifyNoInteractions(evidences, access, events, mapper);
    }

    @Test
    void missingEvidenceUsesTheGenericNotFoundBoundaryWithoutReadingEvents() {
        when(evidences.findByIdForVisibility(EVIDENCE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.list(EVIDENCE_ID, 0, 20, List.of(), actor))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(access, events, mapper);
    }

    @Test
    void visibleEvidenceWithMissingOrMismatchedEventUsesTheEventSpecificNotFoundBoundary() {
        visibleEvidence();
        when(events.findByEvidenceIdAndEventId(EVIDENCE_ID, EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(EVIDENCE_ID, EVENT_ID, actor))
                .isInstanceOf(EventNotFoundException.class)
                .hasMessage("The requested custody event was not found.");

        InOrder order = inOrder(evidences, access, events);
        order.verify(evidences).findByIdForVisibility(EVIDENCE_ID);
        order.verify(access).requireReadableCase(CASE_ID, actor);
        order.verify(events).findByEvidenceIdAndEventId(EVIDENCE_ID, EVENT_ID);
        verifyNoInteractions(mapper);
    }

    private void visibleEvidence() {
        when(evidence.getCustodyCase()).thenReturn(custodyCase);
        when(custodyCase.getId()).thenReturn(CASE_ID);
        when(evidences.findByIdForVisibility(EVIDENCE_ID)).thenReturn(Optional.of(evidence));
        when(access.requireReadableCase(CASE_ID, actor)).thenReturn(custodyCase);
    }

    private static Stream<Arguments> invalidPages() {
        return Stream.of(
                Arguments.of(-1, 20, null),
                Arguments.of(0, 0, null),
                Arguments.of(0, 201, null),
                Arguments.of(0, 20, List.of("sequenceNumber,desc")));
    }
}
