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
import it.itsprodigi.proofchain.custodyevent.protocol.EvidenceReleasedPayload;
import it.itsprodigi.proofchain.evidence.api.EvidenceOperationResponse;
import it.itsprodigi.proofchain.evidence.api.ReleaseEvidenceRequest;
import it.itsprodigi.proofchain.evidence.api.SealEvidenceRequest;
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
 * PostgreSQL behavior of the evidence release workflow: the narrower authorization matrix, both permitted source
 * states, the atomic holder clearing, the deliberate absence of a holder-eligibility requirement, the exact typed
 * payload with its chain linkage and atomic rollback.
 */
class EvidenceReleaseServiceIT extends PostgreSqlIntegrationTest {

    private static final String REASON = "Proceedings closed; custody is terminated.";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private EvidenceReleaseService releases;

    @Autowired
    private EvidenceSealService seals;

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
    private Operator auditor;
    private Operator outsider;
    private CustodyCase owningCase;
    private DigitalEvidence target;

    @BeforeEach
    void setUp() {
        cleanDatabaseInDependencyOrder();
        admin = operators.saveAndFlush(operator("release-admin", OperatorRole.ADMIN));
        manager = operators.saveAndFlush(operator("release-manager", OperatorRole.CASE_MANAGER));
        officer = operators.saveAndFlush(operator("release-officer", OperatorRole.EVIDENCE_OFFICER));
        otherOfficer = operators.saveAndFlush(operator("release-other-officer", OperatorRole.EVIDENCE_OFFICER));
        auditor = operators.saveAndFlush(operator("release-auditor", OperatorRole.AUDITOR));
        outsider = operators.saveAndFlush(operator("release-outsider", OperatorRole.CASE_MANAGER));
        owningCase = custodyCases.saveAndFlush(custodyCase("Release case", manager));
        assign(manager, officer, otherOfficer, auditor);
        target = evidences.saveAndFlush(evidence(owningCase, officer, "RELEASE"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        cleanDatabaseInDependencyOrder();
    }

    /**
     * Both permitted source states end in the same terminal state with a null holder, and the payload always carries
     * the captured previous status and previous holder even though the aggregate no longer has one.
     */
    @ParameterizedTest(name = "from {0}")
    @EnumSource(SourceState.class)
    void releasesFromEveryPermittedSourceStateClearingTheHolderAtomically(SourceState source) {
        long expectedSequence = source.sealFirst ? 2L : 1L;
        if (source.sealFirst) {
            seal(manager);
        }

        EvidenceOperationResponse response = release(manager);

        DigitalEvidence released = reload();
        List<CustodyEvent> timeline = events.findAllByEvidenceIdOrderBySequenceNumberAsc(target.getId());
        CustodyEvent event = timeline.getLast();
        assertThat(response.evidence().status()).isEqualTo(EvidenceStatus.RELEASED);
        assertThat(response.evidence().currentHolder())
                .as("the release response never shows a holder")
                .isNull();
        assertThat(response.eventSummary().eventType()).isEqualTo(EventType.EVIDENCE_RELEASED);
        assertThat(response.eventSummary().sequenceNumber()).isEqualTo(expectedSequence);
        assertThat(response.eventSummary().occurredAt())
                .isEqualTo(response.evidence().updatedAt())
                .isEqualTo(released.getUpdatedAt())
                .isEqualTo(event.getOccurredAt())
                .isEqualTo(event.getOccurredAt().truncatedTo(ChronoUnit.MICROS));
        assertThat(released.getStatus()).isEqualTo(EvidenceStatus.RELEASED);
        assertThat(released.getCurrentHolder())
                .as("the committed aggregate has no holder left")
                .isNull();
        assertThat(currentHolderColumn()).isNull();
        assertThat(released.getCustodyEventCount()).isEqualTo(expectedSequence);
        assertThat(released.getCustodyChainHeadHash()).isEqualTo(event.getEventHash());
        assertThat(event.getPreviousHash())
                .isEqualTo(source.sealFirst ? timeline.getFirst().getEventHash() : CustodyEventHashing.ZERO_HASH);
        assertThat(JSON.readTree(event.getPayloadJson()))
                .isEqualTo(JSON.readTree(CustodyEventCanonicalizer.canonicalizePayload(expectedPayload(source))));
        assertThat(event.getEventHash()).isEqualTo(CustodyEventHashing.eventHash(canonical(event, manager, source)));
    }

    /**
     * Release is the one operational command a member {@code EVIDENCE_OFFICER} may never issue, not even while it is
     * the current holder.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Caller.class)
    void enforcesTheNarrowerRoleAndMembershipMatrix(Caller caller) {
        ThrowingCallable command = () -> release(callerOperator(caller));

        switch (caller.outcome) {
            case ALLOWED -> {
                assertThatCode(command).doesNotThrowAnyException();
                assertThat(reload().getStatus()).isEqualTo(EvidenceStatus.RELEASED);
                assertThat(events.countByEvidenceId(target.getId())).isEqualTo(1L);
            }
            case FORBIDDEN -> {
                assertThatThrownBy(command).isInstanceOf(AccessDeniedException.class);
                assertUnchangedAndEventFree();
            }
            case NOT_FOUND -> {
                assertThatThrownBy(command).isInstanceOf(ResourceNotFoundException.class);
                assertUnchangedAndEventFree();
            }
        }
    }

    /**
     * The decisive difference from sealing: management can always terminate custody, even when the holder became
     * ineligible and no recovery transfer was ever performed. The same aggregate proves both halves.
     */
    @Test
    void releasesEvidenceWhoseHolderIsNoLongerEligibleWhileSealingTheSameEvidenceIsRefused() {
        changeStatus(officer, OperatorStatus.SUSPENDED);

        assertThatThrownBy(() -> seal(manager)).isInstanceOf(EvidenceHolderNotEligibleException.class);
        assertThat(events.countByEvidenceId(target.getId())).isZero();

        EvidenceOperationResponse response = release(manager);

        assertThat(response.evidence().status()).isEqualTo(EvidenceStatus.RELEASED);
        assertThat(response.evidence().currentHolder()).isNull();
        assertThat(reload().getCurrentHolder()).isNull();
        assertThat(JSON.readTree(events.findAllByEvidenceIdOrderBySequenceNumberAsc(target.getId())
                        .getFirst()
                        .getPayloadJson()))
                .as("the suspended previous holder still appears in the payload")
                .isEqualTo(JSON.readTree(
                        CustodyEventCanonicalizer.canonicalizePayload(expectedPayload(SourceState.IN_CUSTODY))));
    }

    @Test
    void rejectsRepeatedReleaseWithoutTreatingItAsSuccessAndWithoutAppendingAnyEvent() {
        release(manager);
        DigitalEvidence released = reload();

        assertThatThrownBy(() -> release(manager)).isInstanceOf(InvalidEvidenceStateException.class);

        DigitalEvidence stillReleased = reload();
        assertThat(events.countByEvidenceId(target.getId())).isEqualTo(1L);
        assertThat(stillReleased.getStatus()).isEqualTo(EvidenceStatus.RELEASED);
        assertThat(stillReleased.getCurrentHolder()).isNull();
        assertThat(stillReleased.getUpdatedAt()).isEqualTo(released.getUpdatedAt());
        assertThat(stillReleased.getCustodyChainHeadHash()).isEqualTo(released.getCustodyChainHeadHash());
    }

    @Test
    void refusesReleaseInAClosedCaseWithoutAppendingAnyEvent() {
        owningCase.close();
        custodyCases.saveAndFlush(owningCase);

        assertThatThrownBy(() -> release(manager)).isInstanceOf(CaseClosedException.class);

        assertUnchangedAndEventFree();
    }

    @Test
    void hidesExistingEvidenceExactlyLikeMissingEvidence() {
        ResourceNotFoundException hidden = notFound(() -> release(outsider));
        ResourceNotFoundException missing = notFound(() -> authenticated(
                outsider,
                () -> releases.release(UUID.randomUUID(), new ReleaseEvidenceRequest(REASON), principal(outsider))));

        assertThat(hidden).hasSameClassAs(missing).hasMessage(missing.getMessage());
        assertUnchangedAndEventFree();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(WriteFailure.class)
    void rollsBackTheStatusHolderTimestampAndEventTogether(WriteFailure failure) {
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

        assertThatThrownBy(() -> release(manager)).isInstanceOf(failure.expected);

        assertUnchangedAndEventFree();
        assertThat(reload().getVersion()).isEqualTo(target.getVersion());

        reset(events, evidences);
        assertThat(release(manager).eventSummary().sequenceNumber()).isEqualTo(1L);
    }

    private enum SourceState {
        IN_CUSTODY(false),
        SEALED(true);

        private final boolean sealFirst;

        SourceState(boolean sealFirst) {
            this.sealFirst = sealFirst;
        }
    }

    private enum Outcome {
        ALLOWED,
        FORBIDDEN,
        NOT_FOUND
    }

    private enum Caller {
        GLOBAL_ADMIN(Outcome.ALLOWED),
        MEMBER_CASE_MANAGER(Outcome.ALLOWED),
        MEMBER_EVIDENCE_OFFICER_HOLDER(Outcome.FORBIDDEN),
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

    private EvidenceOperationResponse release(Operator actor) {
        return authenticated(
                actor, () -> releases.release(target.getId(), new ReleaseEvidenceRequest(REASON), principal(actor)));
    }

    private EvidenceOperationResponse seal(Operator actor) {
        return authenticated(
                actor, () -> seals.seal(target.getId(), new SealEvidenceRequest(REASON), principal(actor)));
    }

    private void assertUnchangedAndEventFree() {
        DigitalEvidence unchanged = reload();
        assertThat(events.countByEvidenceId(target.getId())).isZero();
        assertThat(unchanged.getStatus()).isEqualTo(EvidenceStatus.IN_CUSTODY);
        assertThat(unchanged.getCurrentHolder().getId()).isEqualTo(officer.getId());
        assertThat(unchanged.getCustodyEventCount()).isZero();
        assertThat(unchanged.getCustodyChainHeadHash()).isEqualTo(CustodyEventHashing.ZERO_HASH);
        assertThat(unchanged.getUpdatedAt()).isEqualTo(target.getUpdatedAt());
    }

    private EvidenceReleasedPayload expectedPayload(SourceState source) {
        return new EvidenceReleasedPayload(
                source.sealFirst ? EvidenceStatus.SEALED : EvidenceStatus.IN_CUSTODY,
                EvidenceStatus.RELEASED,
                officer.getId(),
                null,
                REASON);
    }

    private CanonicalCustodyEvent canonical(CustodyEvent event, Operator actor, SourceState source) {
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
                expectedPayload(source),
                event.getPreviousHash());
    }

    private UUID currentHolderColumn() {
        return jdbcTemplate.queryForObject(
                "SELECT current_holder_operator_id FROM digital_evidence WHERE id = ?", UUID.class, target.getId());
    }

    private DigitalEvidence reload() {
        return evidences.findByIdForVisibility(target.getId()).orElseThrow();
    }

    private void changeStatus(Operator operator, OperatorStatus status) {
        Operator managed = operators.findById(operator.getId()).orElseThrow();
        managed.changeStatus(status);
        operators.saveAndFlush(managed);
    }

    private void assign(Operator... members) {
        for (Operator member : members) {
            memberships.save(CaseMembership.assign(owningCase, member, manager));
        }
        memberships.flush();
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
