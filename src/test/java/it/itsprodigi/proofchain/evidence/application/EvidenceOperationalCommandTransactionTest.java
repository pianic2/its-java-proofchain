package it.itsprodigi.proofchain.evidence.application;

import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.custodyCase;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.evidence;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.operator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.custodycase.application.CaseClosedException;
import it.itsprodigi.proofchain.custodycase.domain.CaseStatus;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodyevent.application.CustodyEventAppendResult;
import it.itsprodigi.proofchain.custodyevent.application.CustodyEventAppender;
import it.itsprodigi.proofchain.custodyevent.domain.EventType;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyTransferredPayload;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceRepository;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvidenceOperationalCommandTransactionTest {

    private static final Instant COMMAND_INSTANT = Instant.parse("2099-01-31T09:00:00.123456789Z");

    @Mock
    private EvidenceOperationalAccessService access;

    @Mock
    private EvidenceCommandLockService locks;

    @Mock
    private CustodyEventAppender custodyEvents;

    @Mock
    private DigitalEvidenceRepository evidences;

    private EvidenceOperationalCommandTransaction transaction;
    private Operator actor;
    private Operator otherHolder;
    private CustodyCase owningCase;
    private DigitalEvidence target;
    private AuthenticatedOperator principal;
    private UUID caseId;
    private UUID evidenceId;

    @BeforeEach
    void setUp() {
        transaction = new EvidenceOperationalCommandTransaction(
                access,
                locks,
                custodyEvents,
                evidences,
                new EvidenceCommandResponseMapper(new EvidenceMapper()),
                Clock.fixed(COMMAND_INSTANT, ZoneOffset.UTC));
        actor = operator("template-actor", OperatorRole.CASE_MANAGER);
        otherHolder = operator("template-holder", OperatorRole.EVIDENCE_OFFICER);
        owningCase = custodyCase("Template case", actor);
        target = evidence(owningCase, otherHolder, "TPL");
        principal = principal(actor);
        caseId = owningCase.getId();
        evidenceId = target.getId();
    }

    @Test
    void followsTheFrozenLockAndCheckOrderAndSharesOneMicrosecondInstant() {
        stubHappyPath();
        List<Instant> bodyInstants = new ArrayList<>();

        var response =
                transaction.execute(EvidenceOperationalCommand.CUSTODY_TRANSFER, evidenceId, principal, context -> {
                    bodyInstants.add(context.occurredAt());
                    return transferPayload();
                });

        InOrder order = inOrder(access, locks, custodyEvents, evidences);
        order.verify(access).requireVisibleCaseId(evidenceId, principal);
        order.verify(locks).lockCase(caseId);
        order.verify(access).requireAuthorizedActor(EvidenceOperationalCommand.CUSTODY_TRANSFER, caseId, principal);
        order.verify(locks).lockEvidence(any(CaseReadLock.class), eq(evidenceId));
        order.verify(access).requireAuthorizedHolder(EvidenceOperationalCommand.CUSTODY_TRANSFER, actor, target);
        order.verify(custodyEvents)
                .append(eq(evidenceId), eq(actor), any(CustodyEventPayload.class), any(Instant.class));
        order.verify(evidences).saveAndFlush(target);

        ArgumentCaptor<Instant> appended = ArgumentCaptor.forClass(Instant.class);
        verify(custodyEvents).append(eq(evidenceId), eq(actor), any(CustodyEventPayload.class), appended.capture());
        Instant shared = appended.getValue();
        assertThat(shared).isEqualTo(COMMAND_INSTANT.truncatedTo(ChronoUnit.MICROS));
        assertThat(bodyInstants).containsExactly(shared);
        assertThat(target.getUpdatedAt()).isEqualTo(shared);
        assertThat(response.evidence().updatedAt()).isEqualTo(shared);
        assertThat(response.eventSummary().occurredAt()).isEqualTo(shared);
    }

    @Test
    void closedCaseIsRejectedAfterTheCaseLockAndBeforeTheEvidenceLock() {
        owningCase.close();
        when(access.requireVisibleCaseId(evidenceId, principal)).thenReturn(caseId);
        when(locks.lockCase(caseId)).thenReturn(new CaseReadLock(owningCase));
        when(access.requireAuthorizedActor(EvidenceOperationalCommand.METADATA_UPDATE, caseId, principal))
                .thenReturn(actor);

        assertThatThrownBy(() -> transaction.execute(
                        EvidenceOperationalCommand.METADATA_UPDATE,
                        evidenceId,
                        principal,
                        context -> transferPayload()))
                .isInstanceOf(CaseClosedException.class);

        assertThat(owningCase.getStatus()).isEqualTo(CaseStatus.CLOSED);
        verify(locks, never()).lockEvidence(any(), any());
        verify(custodyEvents, never()).append(any(), any(), any(), any());
    }

    @Test
    void releasedEvidenceRejectsMutatingCommandsAfterTheEvidenceLock() {
        target.release();
        when(access.requireVisibleCaseId(evidenceId, principal)).thenReturn(caseId);
        when(locks.lockCase(caseId)).thenReturn(new CaseReadLock(owningCase));
        when(access.requireAuthorizedActor(EvidenceOperationalCommand.EVIDENCE_SEAL, caseId, principal))
                .thenReturn(actor);
        when(locks.lockEvidence(any(CaseReadLock.class), eq(evidenceId))).thenReturn(target);

        assertThatThrownBy(() -> transaction.execute(
                        EvidenceOperationalCommand.EVIDENCE_SEAL, evidenceId, principal, context -> transferPayload()))
                .isInstanceOf(InvalidEvidenceStateException.class);

        verify(custodyEvents, never()).append(any(), any(), any(), any());
        verify(evidences, never()).saveAndFlush(any());
    }

    private void stubHappyPath() {
        when(access.requireVisibleCaseId(evidenceId, principal)).thenReturn(caseId);
        when(locks.lockCase(caseId)).thenReturn(new CaseReadLock(owningCase));
        when(access.requireAuthorizedActor(EvidenceOperationalCommand.CUSTODY_TRANSFER, caseId, principal))
                .thenReturn(actor);
        when(locks.lockEvidence(any(CaseReadLock.class), eq(evidenceId))).thenReturn(target);
        when(custodyEvents.append(eq(evidenceId), eq(actor), any(CustodyEventPayload.class), any(Instant.class)))
                .thenAnswer(invocation -> new CustodyEventAppendResult(
                        UUID.randomUUID(),
                        2L,
                        EventType.CUSTODY_TRANSFERRED,
                        invocation.getArgument(3),
                        "0".repeat(64),
                        "a".repeat(64),
                        1,
                        1));
    }

    private CustodyTransferredPayload transferPayload() {
        return new CustodyTransferredPayload(otherHolder.getId(), actor.getId(), "handover for analysis");
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
