package it.itsprodigi.proofchain.custodyevent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.common.exception.ResourceNotFoundException;
import it.itsprodigi.proofchain.custodycase.application.CaseAccessService;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodyevent.api.CustodyChainVerificationResponse;
import it.itsprodigi.proofchain.custodyevent.domain.CustodyEvent;
import it.itsprodigi.proofchain.custodyevent.persistence.CustodyEventRepository;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceRepository;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustodyChainVerificationServiceTest {

    private static final UUID CASE_ID = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final UUID EVIDENCE_ID = UUID.fromString("80000000-0000-4000-8000-000000000001");
    private static final Instant FIXED_INSTANT = Instant.parse("2026-07-30T09:15:00.123456789Z");

    @Mock
    private DigitalEvidenceRepository evidences;

    @Mock
    private CustodyEventRepository events;

    @Mock
    private CaseAccessService access;

    @Mock
    private CustodyChainVerifier verifier;

    @Mock
    private DigitalEvidence visibilityEvidence;

    @Mock
    private DigitalEvidence lockedEvidence;

    @Mock
    private CustodyCase custodyCase;

    @Mock
    private EntityManager entityManager;

    private CustodyChainVerificationService service;
    private AuthenticatedOperator actor;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        service = new CustodyChainVerificationService(evidences, events, access, verifier, entityManager, clock);
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
    void missingEvidenceUsesTheGenericNotFoundBoundaryWithoutReadingEventsOrLocking() {
        when(evidences.findByIdForVisibility(EVIDENCE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyChain(EVIDENCE_ID, actor)).isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(access, events, verifier);
    }

    @Test
    void resolvesVisibilityThenLocksBeforeVerifyingAndNeverInvokesAnyWriteRepositoryMethod() {
        CustodyEvent event = mock(CustodyEvent.class);
        List<CustodyEvent> loaded = List.of(event);
        UUID eventId = UUID.randomUUID();
        CustodyChainVerificationResult result = new CustodyChainVerificationResult(
                EVIDENCE_ID, true, 1, 1, 1, "a".repeat(64), "a".repeat(64), null, null, null, null, null);
        when(visibilityEvidence.getCustodyCase()).thenReturn(custodyCase);
        when(custodyCase.getId()).thenReturn(CASE_ID);
        when(evidences.findByIdForVisibility(EVIDENCE_ID)).thenReturn(Optional.of(visibilityEvidence));
        when(access.requireReadableCase(CASE_ID, actor)).thenReturn(custodyCase);
        when(evidences.findByIdForChainVerification(EVIDENCE_ID)).thenReturn(Optional.of(lockedEvidence));
        when(lockedEvidence.getCustodyEventCount()).thenReturn(1L);
        when(lockedEvidence.getCustodyChainHeadHash()).thenReturn("a".repeat(64));
        when(events.findAllByEvidenceIdOrderBySequenceNumberAsc(EVIDENCE_ID)).thenReturn(loaded);
        stubSnapshotFields(event, eventId);
        when(verifier.verify(EVIDENCE_ID, CASE_ID, 1L, "a".repeat(64), List.of(snapshotOf(event))))
                .thenReturn(result);

        CustodyChainVerificationResponse response = service.verifyChain(EVIDENCE_ID, actor);

        assertThat(response.evidenceId()).isEqualTo(EVIDENCE_ID);
        assertThat(response.valid()).isTrue();
        assertThat(response.checkedEvents()).isEqualTo(1);
        assertThat(response.storedEventCount()).isEqualTo(1);
        assertThat(response.loadedEventCount()).isEqualTo(1);
        assertThat(response.storedHeadHash()).isEqualTo("a".repeat(64));
        assertThat(response.calculatedHeadHash()).isEqualTo("a".repeat(64));
        assertThat(response.brokenAtEventId()).isNull();
        assertThat(response.brokenAtSequenceNumber()).isNull();
        assertThat(response.reason()).isNull();
        assertThat(response.expectedValue()).isNull();
        assertThat(response.actualValue()).isNull();
        assertThat(response.verifiedAt()).isEqualTo(FIXED_INSTANT.truncatedTo(java.time.temporal.ChronoUnit.MICROS));

        InOrder order = inOrder(evidences, access, entityManager, events, verifier);
        order.verify(evidences).findByIdForVisibility(EVIDENCE_ID);
        order.verify(access).requireReadableCase(CASE_ID, actor);
        order.verify(entityManager).detach(visibilityEvidence);
        order.verify(evidences).findByIdForChainVerification(EVIDENCE_ID);
        order.verify(events).findAllByEvidenceIdOrderBySequenceNumberAsc(EVIDENCE_ID);
        order.verify(verifier).verify(EVIDENCE_ID, CASE_ID, 1L, "a".repeat(64), List.of(snapshotOf(event)));
        verifyNoMoreInteractions(evidences, events);
    }

    private static void stubSnapshotFields(CustodyEvent event, UUID eventId) {
        CustodyCase eventCase = mock(CustodyCase.class);
        DigitalEvidence eventEvidence = mock(DigitalEvidence.class);
        it.itsprodigi.proofchain.operator.domain.Operator operator =
                mock(it.itsprodigi.proofchain.operator.domain.Operator.class);
        UUID operatorId = UUID.fromString("90000000-0000-4000-8000-000000000001");
        when(event.getId()).thenReturn(eventId);
        when(event.getCustodyCase()).thenReturn(eventCase);
        when(eventCase.getId()).thenReturn(CASE_ID);
        when(event.getEvidence()).thenReturn(eventEvidence);
        when(eventEvidence.getId()).thenReturn(EVIDENCE_ID);
        when(event.getOperator()).thenReturn(operator);
        when(operator.getId()).thenReturn(operatorId);
        when(event.getActorRole()).thenReturn(OperatorRole.EVIDENCE_OFFICER);
        when(event.getSequenceNumber()).thenReturn(1L);
        when(event.getEventType())
                .thenReturn(it.itsprodigi.proofchain.custodyevent.domain.EventType.EVIDENCE_REGISTERED);
        when(event.getOccurredAt()).thenReturn(Instant.parse("2026-07-29T12:34:56.123456Z"));
        when(event.getHashVersion()).thenReturn(1);
        when(event.getPayloadVersion()).thenReturn(1);
        when(event.getPayloadJson()).thenReturn("{}");
        when(event.getPreviousHash()).thenReturn("0".repeat(64));
        when(event.getEventHash()).thenReturn("a".repeat(64));
    }

    private static CustodyEventSnapshot snapshotOf(CustodyEvent event) {
        return new CustodyEventSnapshot(
                event.getId(),
                event.getCustodyCase().getId(),
                event.getEvidence().getId(),
                event.getOperator().getId(),
                event.getActorRole(),
                event.getSequenceNumber(),
                event.getEventType(),
                event.getOccurredAt(),
                event.getHashVersion(),
                event.getPayloadVersion(),
                event.getPayloadJson(),
                event.getPreviousHash(),
                event.getEventHash());
    }
}
