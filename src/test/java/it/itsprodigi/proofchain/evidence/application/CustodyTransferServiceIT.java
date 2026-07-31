package it.itsprodigi.proofchain.evidence.application;

import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.custodyCase;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.evidence;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.operator;
import static it.itsprodigi.proofchain.support.OperationalCommandTestSupport.authenticated;
import static it.itsprodigi.proofchain.support.OperationalCommandTestSupport.principal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import it.itsprodigi.proofchain.common.exception.ResourceNotFoundException;
import it.itsprodigi.proofchain.custodycase.application.CaseClosedException;
import it.itsprodigi.proofchain.custodycase.domain.CaseMembership;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.custodycase.persistence.CustodyCaseRepository;
import it.itsprodigi.proofchain.custodyevent.application.CustodyEventConcurrencyConflictException;
import it.itsprodigi.proofchain.custodyevent.application.CustodyEventPersistenceFailureException;
import it.itsprodigi.proofchain.custodyevent.domain.CustodyEvent;
import it.itsprodigi.proofchain.custodyevent.domain.EventType;
import it.itsprodigi.proofchain.custodyevent.persistence.CustodyEventRepository;
import it.itsprodigi.proofchain.custodyevent.protocol.CanonicalCustodyEvent;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventCanonicalizer;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventHashing;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyTransferredPayload;
import it.itsprodigi.proofchain.evidence.api.EvidenceOperationResponse;
import it.itsprodigi.proofchain.evidence.api.TransferCustodyRequest;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.domain.EvidenceStatus;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceRepository;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL behavior of the custody transfer workflow: authorization matrix, holder-only mutation, sealed preservation,
 * lifecycle and case guards, the no-op conflict, the exact typed payload with its chain linkage and atomic rollback.
 */
class CustodyTransferServiceIT extends PostgreSqlIntegrationTest {

    private static final String REASON = "Handover to the laboratory analyst.";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private CustodyTransferService transfers;

    @MockitoSpyBean
    private CustodyEventRepository events;

    @MockitoSpyBean
    private DigitalEvidenceRepository evidences;

    @Autowired
    private CaseMembershipRepository memberships;

    @Autowired
    private CustodyCaseRepository custodyCases;

    @Autowired
    private OperatorRepository operators;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Operator admin;
    private Operator manager;
    private Operator officer;
    private Operator otherOfficer;
    private Operator analyst;
    private Operator auditor;
    private Operator outsider;
    private CustodyCase owningCase;
    private DigitalEvidence target;

    @BeforeEach
    void setUp() {
        cleanDatabaseInDependencyOrder();
        admin = operators.saveAndFlush(operator("transfer-admin", OperatorRole.ADMIN));
        manager = operators.saveAndFlush(operator("transfer-manager", OperatorRole.CASE_MANAGER));
        officer = operators.saveAndFlush(operator("transfer-officer", OperatorRole.EVIDENCE_OFFICER));
        otherOfficer = operators.saveAndFlush(operator("transfer-other-officer", OperatorRole.EVIDENCE_OFFICER));
        analyst = operators.saveAndFlush(operator("transfer-analyst", OperatorRole.EVIDENCE_OFFICER));
        auditor = operators.saveAndFlush(operator("transfer-auditor", OperatorRole.AUDITOR));
        outsider = operators.saveAndFlush(operator("transfer-outsider", OperatorRole.CASE_MANAGER));
        owningCase = custodyCases.saveAndFlush(custodyCase("Transfer case", manager));
        assign(manager, officer, otherOfficer, analyst, auditor);
        target = evidences.saveAndFlush(evidence(owningCase, officer, "TRANSFER"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        cleanDatabaseInDependencyOrder();
    }

    @Test
    void appendsOneTypedCustodyTransferredEventLinkedToTheChainWithOneSharedInstant() {
        EvidenceOperationResponse first = transfer(manager, manager);

        DigitalEvidence afterFirst = reload();
        CustodyEvent firstEvent = events.findAllByEvidenceIdOrderBySequenceNumberAsc(target.getId())
                .getFirst();
        assertThat(first.evidence().currentHolder().id()).isEqualTo(manager.getId());
        assertThat(first.evidence().status()).isEqualTo(EvidenceStatus.IN_CUSTODY);
        assertThat(first.eventSummary().eventType()).isEqualTo(EventType.CUSTODY_TRANSFERRED);
        assertThat(first.eventSummary().sequenceNumber()).isEqualTo(1L);
        assertThat(first.eventSummary().previousHash()).isEqualTo(CustodyEventHashing.ZERO_HASH);
        assertThat(first.eventSummary().occurredAt())
                .isEqualTo(first.evidence().updatedAt())
                .isEqualTo(afterFirst.getUpdatedAt())
                .isEqualTo(firstEvent.getOccurredAt())
                .isEqualTo(firstEvent.getOccurredAt().truncatedTo(ChronoUnit.MICROS));
        assertThat(afterFirst.getCurrentHolder().getId()).isEqualTo(manager.getId());
        assertThat(afterFirst.getCustodyEventCount()).isEqualTo(1L);
        assertThat(afterFirst.getCustodyChainHeadHash()).isEqualTo(firstEvent.getEventHash());
        assertThat(JSON.readTree(firstEvent.getPayloadJson()))
                .isEqualTo(JSON.readTree(CustodyEventCanonicalizer.canonicalizePayload(
                        new CustodyTransferredPayload(officer.getId(), manager.getId(), REASON))));
        assertThat(firstEvent.getEventHash())
                .isEqualTo(CustodyEventHashing.eventHash(canonical(
                        firstEvent, manager, new CustodyTransferredPayload(officer.getId(), manager.getId(), REASON))));

        EvidenceOperationResponse second = transfer(manager, otherOfficer);

        List<CustodyEvent> timeline = events.findAllByEvidenceIdOrderBySequenceNumberAsc(target.getId());
        DigitalEvidence afterSecond = reload();
        assertThat(second.eventSummary().sequenceNumber()).isEqualTo(2L);
        assertThat(timeline).extracting(CustodyEvent::getSequenceNumber).containsExactly(1L, 2L);
        assertThat(timeline.getLast().getPreviousHash())
                .isEqualTo(timeline.getFirst().getEventHash());
        assertThat(JSON.readTree(timeline.getLast().getPayloadJson()))
                .isEqualTo(JSON.readTree(CustodyEventCanonicalizer.canonicalizePayload(
                        new CustodyTransferredPayload(manager.getId(), otherOfficer.getId(), REASON))));
        assertThat(afterSecond.getCustodyChainHeadHash())
                .isEqualTo(timeline.getLast().getEventHash());
        assertThat(afterSecond.getCurrentHolder().getId()).isEqualTo(otherOfficer.getId());
        assertThat(afterSecond.getStatus()).isEqualTo(EvidenceStatus.IN_CUSTODY);
    }

    @Test
    void keepsSealedEvidenceSealedAndRefusesReleasedEvidence() {
        target.seal();
        target = evidences.saveAndFlush(target);

        EvidenceOperationResponse response = transfer(manager, manager);

        assertThat(response.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        assertThat(response.eventSummary().eventType()).isEqualTo(EventType.CUSTODY_TRANSFERRED);
        assertThat(reload().getStatus()).isEqualTo(EvidenceStatus.SEALED);
        assertThat(events.findAllByEvidenceIdOrderBySequenceNumberAsc(target.getId()))
                .extracting(CustodyEvent::getEventType)
                .containsExactly(EventType.CUSTODY_TRANSFERRED);

        DigitalEvidence released = evidences.saveAndFlush(evidence(owningCase, officer, "RELEASED"));
        released.release();
        released = evidences.saveAndFlush(released);
        UUID releasedId = released.getId();

        assertThatThrownBy(() -> authenticated(
                        manager,
                        () -> transfers.transfer(
                                releasedId, new TransferCustodyRequest(manager.getId(), REASON), principal(manager))))
                .isInstanceOf(InvalidEvidenceStateException.class);
        assertThat(events.countByEvidenceId(releasedId)).isZero();
    }

    @Test
    void refusesTransfersInAClosedCaseWithoutAppendingAnyEvent() {
        owningCase.close();
        custodyCases.saveAndFlush(owningCase);

        assertThatThrownBy(() -> transfer(manager, manager)).isInstanceOf(CaseClosedException.class);

        assertThat(events.countByEvidenceId(target.getId())).isZero();
        assertThat(reload().getCurrentHolder().getId()).isEqualTo(officer.getId());
    }

    @Test
    void refusesATransferToTheCurrentHolderWithoutAppendingAnyEvent() {
        assertThatThrownBy(() -> transfer(manager, officer)).isInstanceOf(CustodyTransferNoOpException.class);

        DigitalEvidence unchanged = reload();
        assertThat(events.countByEvidenceId(target.getId())).isZero();
        assertThat(unchanged.getCustodyEventCount()).isZero();
        assertThat(unchanged.getCustodyChainHeadHash()).isEqualTo(CustodyEventHashing.ZERO_HASH);
        assertThat(unchanged.getCurrentHolder().getId()).isEqualTo(officer.getId());
        assertThat(unchanged.getUpdatedAt()).isEqualTo(target.getUpdatedAt());
        assertThat(unchanged.getVersion()).isEqualTo(target.getVersion());
    }

    /**
     * Caller role, case membership and current-holder state completely determine the outcome. The allowed
     * {@code MEMBER_CASE_MANAGER} row is also the self-selection case: the caller becomes the new holder.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Caller.class)
    void enforcesTheCompleteRoleMembershipAndCurrentHolderMatrix(Caller caller) {
        Operator actor = callerOperator(caller);
        ThrowingCallable command = () -> transfer(actor, manager);

        switch (caller.outcome) {
            case ALLOWED -> {
                assertThatCode(command).doesNotThrowAnyException();
                assertThat(reload().getCurrentHolder().getId()).isEqualTo(manager.getId());
                assertThat(events.countByEvidenceId(target.getId())).isEqualTo(1L);
            }
            case FORBIDDEN -> {
                assertThatThrownBy(command).isInstanceOf(AccessDeniedException.class);
                assertThat(events.countByEvidenceId(target.getId())).isZero();
            }
            case NOT_FOUND -> {
                assertThatThrownBy(command).isInstanceOf(ResourceNotFoundException.class);
                assertThat(events.countByEvidenceId(target.getId())).isZero();
            }
        }
    }

    @Test
    void hidesExistingEvidenceExactlyLikeMissingEvidence() {
        ResourceNotFoundException hidden = notFound(() -> transfer(outsider, manager));
        ResourceNotFoundException missing = notFound(() -> authenticated(
                outsider,
                () -> transfers.transfer(
                        UUID.randomUUID(), new TransferCustodyRequest(manager.getId(), REASON), principal(outsider))));

        assertThat(hidden).hasSameClassAs(missing).hasMessage(missing.getMessage());
        assertThat(events.countByEvidenceId(target.getId())).isZero();
    }

    @Test
    void allowsAdminAndMemberCaseManagerToRecoverEvidenceFromAnIneligibleHolder() {
        suspend(officer);

        EvidenceOperationResponse recoveredByAdmin = transfer(admin, analyst);

        assertThat(recoveredByAdmin.evidence().currentHolder().id()).isEqualTo(analyst.getId());
        suspend(analyst);

        EvidenceOperationResponse recoveredByManager = transfer(manager, otherOfficer);

        assertThat(recoveredByManager.evidence().currentHolder().id()).isEqualTo(otherOfficer.getId());
        assertThat(reload().getCurrentHolder().getId()).isEqualTo(otherOfficer.getId());
        assertThat(events.countByEvidenceId(target.getId())).isEqualTo(2L);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(WriteFailure.class)
    void rollsBackTheHolderChangeAndTheEventTogether(WriteFailure failure) {
        switch (failure) {
            case APPENDER_INSERT ->
                doThrow(new DataIntegrityViolationException("forced custody event insert failure"))
                        .when(events)
                        .saveAndFlush(any(CustodyEvent.class));
            case AGGREGATE_FLUSH ->
                doThrow(new DataIntegrityViolationException("forced aggregate flush failure"))
                        .when(evidences)
                        .saveAndFlush(any(DigitalEvidence.class));
            case OPTIMISTIC_CONFLICT ->
                doThrow(new ObjectOptimisticLockingFailureException(DigitalEvidence.class, target.getId()))
                        .when(evidences)
                        .saveAndFlush(any(DigitalEvidence.class));
        }

        assertThatThrownBy(() -> transfer(manager, manager)).isInstanceOf(failure.expected);

        DigitalEvidence unchanged = reload();
        assertThat(events.countByEvidenceId(target.getId())).isZero();
        assertThat(unchanged.getCustodyEventCount()).isZero();
        assertThat(unchanged.getCustodyChainHeadHash()).isEqualTo(CustodyEventHashing.ZERO_HASH);
        assertThat(unchanged.getCurrentHolder().getId()).isEqualTo(officer.getId());
        assertThat(unchanged.getUpdatedAt()).isEqualTo(target.getUpdatedAt());

        reset(events, evidences);
        assertThat(transfer(manager, manager).eventSummary().sequenceNumber()).isEqualTo(1L);
    }

    private enum Outcome {
        ALLOWED,
        FORBIDDEN,
        NOT_FOUND
    }

    private enum Caller {
        GLOBAL_ADMIN(Outcome.ALLOWED),
        MEMBER_CASE_MANAGER(Outcome.ALLOWED),
        MEMBER_EVIDENCE_OFFICER_HOLDER(Outcome.ALLOWED),
        MEMBER_EVIDENCE_OFFICER_NON_HOLDER(Outcome.FORBIDDEN),
        MEMBER_AUDITOR(Outcome.FORBIDDEN),
        NON_MEMBER_CASE_MANAGER(Outcome.NOT_FOUND);

        private final Outcome outcome;

        Caller(Outcome outcome) {
            this.outcome = outcome;
        }
    }

    private enum WriteFailure {
        APPENDER_INSERT(CustodyEventPersistenceFailureException.class),
        AGGREGATE_FLUSH(CustodyEventPersistenceFailureException.class),
        OPTIMISTIC_CONFLICT(CustodyEventConcurrencyConflictException.class);

        private final Class<? extends RuntimeException> expected;

        WriteFailure(Class<? extends RuntimeException> expected) {
            this.expected = expected;
        }
    }

    private Operator callerOperator(Caller caller) {
        return switch (caller) {
            case GLOBAL_ADMIN -> admin;
            case MEMBER_CASE_MANAGER -> manager;
            case MEMBER_EVIDENCE_OFFICER_HOLDER -> officer;
            case MEMBER_EVIDENCE_OFFICER_NON_HOLDER -> otherOfficer;
            case MEMBER_AUDITOR -> auditor;
            case NON_MEMBER_CASE_MANAGER -> outsider;
        };
    }

    private EvidenceOperationResponse transfer(Operator actor, Operator newHolder) {
        return authenticated(
                actor,
                () -> transfers.transfer(
                        target.getId(), new TransferCustodyRequest(newHolder.getId(), REASON), principal(actor)));
    }

    private void suspend(Operator operator) {
        Operator managed = operators.findById(operator.getId()).orElseThrow();
        managed.changeStatus(OperatorStatus.SUSPENDED);
        operators.saveAndFlush(managed);
    }

    private void assign(Operator... members) {
        for (Operator member : members) {
            memberships.save(CaseMembership.assign(owningCase, member, manager));
        }
        memberships.flush();
    }

    private DigitalEvidence reload() {
        return evidences.findByIdForVisibility(target.getId()).orElseThrow();
    }

    private CanonicalCustodyEvent canonical(CustodyEvent event, Operator actor, CustodyTransferredPayload payload) {
        return new CanonicalCustodyEvent(
                event.getId(),
                owningCase.getId(),
                target.getId(),
                actor.getId(),
                actor.getRole(),
                event.getSequenceNumber(),
                event.getEventType(),
                event.getOccurredAt(),
                event.getPayloadVersion(),
                payload,
                event.getPreviousHash());
    }

    private static ResourceNotFoundException notFound(ThrowingCallable action) {
        try {
            action.call();
        } catch (ResourceNotFoundException exception) {
            return exception;
        } catch (Throwable failure) {
            throw new AssertionError("Expected a ResourceNotFoundException", failure);
        }
        throw new AssertionError("Expected a ResourceNotFoundException");
    }

    private void cleanDatabaseInDependencyOrder() {
        jdbcTemplate.execute("TRUNCATE TABLE custody_events");
        evidences.deleteAllInBatch();
        memberships.deleteAllInBatch();
        custodyCases.deleteAllInBatch();
        operators.deleteAllInBatch();
    }
}
