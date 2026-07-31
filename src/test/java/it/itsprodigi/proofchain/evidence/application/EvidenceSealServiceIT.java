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
import it.itsprodigi.proofchain.custodyevent.protocol.EvidenceSealedPayload;
import it.itsprodigi.proofchain.evidence.api.EvidenceOperationResponse;
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
 * PostgreSQL behavior of the evidence seal workflow: the authorization matrix, the {@code IN_CUSTODY -> SEALED} edge,
 * the current-holder eligibility requirement, holder preservation, the exact typed payload with its chain linkage and
 * atomic rollback.
 */
class EvidenceSealServiceIT extends PostgreSqlIntegrationTest {

    private static final String REASON = "Analysis completed; the working copy is sealed.";
    private static final JsonMapper JSON = JsonMapper.builder().build();

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
        admin = operators.saveAndFlush(operator("seal-admin", OperatorRole.ADMIN));
        manager = operators.saveAndFlush(operator("seal-manager", OperatorRole.CASE_MANAGER));
        officer = operators.saveAndFlush(operator("seal-officer", OperatorRole.EVIDENCE_OFFICER));
        otherOfficer = operators.saveAndFlush(operator("seal-other-officer", OperatorRole.EVIDENCE_OFFICER));
        auditor = operators.saveAndFlush(operator("seal-auditor", OperatorRole.AUDITOR));
        outsider = operators.saveAndFlush(operator("seal-outsider", OperatorRole.CASE_MANAGER));
        owningCase = custodyCases.saveAndFlush(custodyCase("Seal case", manager));
        assign(manager, officer, otherOfficer, auditor);
        target = evidences.saveAndFlush(evidence(owningCase, officer, "SEAL"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        cleanDatabaseInDependencyOrder();
    }

    @Test
    void sealsInCustodyEvidenceKeepingTheHolderAndAppendsOneTypedEventWithOneSharedInstant() {
        EvidenceOperationResponse response = seal(manager);

        DigitalEvidence sealed = reload();
        CustodyEvent event = events.findAllByEvidenceIdOrderBySequenceNumberAsc(target.getId())
                .getFirst();
        assertThat(response.evidence().status()).isEqualTo(EvidenceStatus.SEALED);
        assertThat(response.evidence().currentHolder()).isNotNull();
        assertThat(response.evidence().currentHolder().id())
                .as("sealing never changes the holder")
                .isEqualTo(officer.getId());
        assertThat(response.eventSummary().eventType()).isEqualTo(EventType.EVIDENCE_SEALED);
        assertThat(response.eventSummary().sequenceNumber()).isEqualTo(1L);
        assertThat(response.eventSummary().previousHash()).isEqualTo(CustodyEventHashing.ZERO_HASH);
        assertThat(response.eventSummary().occurredAt())
                .isEqualTo(response.evidence().updatedAt())
                .isEqualTo(sealed.getUpdatedAt())
                .isEqualTo(event.getOccurredAt())
                .isEqualTo(event.getOccurredAt().truncatedTo(ChronoUnit.MICROS));
        assertThat(sealed.getStatus()).isEqualTo(EvidenceStatus.SEALED);
        assertThat(sealed.getCurrentHolder().getId()).isEqualTo(officer.getId());
        assertThat(sealed.getCustodyEventCount()).isEqualTo(1L);
        assertThat(sealed.getCustodyChainHeadHash()).isEqualTo(event.getEventHash());
        assertThat(JSON.readTree(event.getPayloadJson()))
                .isEqualTo(JSON.readTree(CustodyEventCanonicalizer.canonicalizePayload(expectedPayload())));
        assertThat(event.getEventHash()).isEqualTo(CustodyEventHashing.eventHash(canonical(event, manager)));
    }

    /**
     * Caller role, case membership and current-holder state completely determine the outcome. Only a member
     * {@code EVIDENCE_OFFICER} that is the current holder may seal; every other officer and every auditor is forbidden.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Caller.class)
    void enforcesTheCompleteRoleMembershipAndCurrentHolderMatrix(Caller caller) {
        ThrowingCallable command = () -> seal(callerOperator(caller));

        switch (caller.outcome) {
            case ALLOWED -> {
                assertThatCode(command).doesNotThrowAnyException();
                assertThat(reload().getStatus()).isEqualTo(EvidenceStatus.SEALED);
                assertThat(reload().getCurrentHolder().getId()).isEqualTo(officer.getId());
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
     * Sealing freezes the custody chain around whoever currently holds the evidence, so the holder must still be an
     * eligible member. Every cause is indistinguishable and none of them ever changes or clears the holder: an explicit
     * recovery transfer is the only way forward.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(IneligibleHolder.class)
    void mapsEveryCurrentHolderIneligibilityCauseToTheSameConflictWithoutTouchingTheHolder(IneligibleHolder cause) {
        makeHolderIneligible(cause);

        assertThatThrownBy(() -> seal(manager)).isInstanceOf(EvidenceHolderNotEligibleException.class);
        assertThatThrownBy(() -> seal(admin)).isInstanceOf(EvidenceHolderNotEligibleException.class);

        assertUnchangedAndEventFree();
        assertThat(reload().getCurrentHolder().getId())
                .as("an ineligible holder is never automatically replaced or cleared")
                .isEqualTo(holderOf(cause));
    }

    @Test
    void rejectsRepeatedSealAndSealOfReleasedEvidenceWithoutAppendingAnyEvent() {
        seal(manager);

        assertThatThrownBy(() -> seal(manager)).isInstanceOf(InvalidEvidenceStateException.class);
        assertThat(events.countByEvidenceId(target.getId())).isEqualTo(1L);
        assertThat(reload().getStatus()).isEqualTo(EvidenceStatus.SEALED);

        DigitalEvidence released = evidences.saveAndFlush(evidence(owningCase, officer, "SEAL.RELEASED"));
        released.release();
        released = evidences.saveAndFlush(released);
        UUID releasedId = released.getId();

        assertThatThrownBy(() -> authenticated(
                        manager, () -> seals.seal(releasedId, new SealEvidenceRequest(REASON), principal(manager))))
                .isInstanceOf(InvalidEvidenceStateException.class);
        assertThat(events.countByEvidenceId(releasedId)).isZero();
        assertThat(evidences.findByIdForVisibility(releasedId).orElseThrow().getStatus())
                .isEqualTo(EvidenceStatus.RELEASED);
    }

    @Test
    void refusesSealInAClosedCaseWithoutAppendingAnyEvent() {
        owningCase.close();
        custodyCases.saveAndFlush(owningCase);

        assertThatThrownBy(() -> seal(manager)).isInstanceOf(CaseClosedException.class);

        assertUnchangedAndEventFree();
    }

    @Test
    void hidesExistingEvidenceExactlyLikeMissingEvidence() {
        ResourceNotFoundException hidden = notFound(() -> seal(outsider));
        ResourceNotFoundException missing = notFound(() -> authenticated(
                outsider, () -> seals.seal(UUID.randomUUID(), new SealEvidenceRequest(REASON), principal(outsider))));

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

        assertThatThrownBy(() -> seal(manager)).isInstanceOf(failure.expected);

        assertUnchangedAndEventFree();
        assertThat(reload().getVersion()).isEqualTo(target.getVersion());

        reset(events, evidences);
        assertThat(seal(manager).eventSummary().sequenceNumber()).isEqualTo(1L);
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

    private enum IneligibleHolder {
        HOLDER_SUSPENDED,
        HOLDER_DISABLED,
        HOLDER_NO_LONGER_MEMBER,
        HOLDER_ROLE_NOT_CUSTODY_CAPABLE
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

    /**
     * The auditor row is the only one that needs a different holder, because an auditor can never become the holder of
     * evidence through the transfer workflow; here it is installed directly to exercise the role gate of the query.
     */
    private void makeHolderIneligible(IneligibleHolder cause) {
        switch (cause) {
            case HOLDER_SUSPENDED -> changeStatus(officer, OperatorStatus.SUSPENDED);
            case HOLDER_DISABLED -> changeStatus(officer, OperatorStatus.DISABLED);
            case HOLDER_NO_LONGER_MEMBER -> {
                memberships
                        .findByCaseIdAndOperatorId(owningCase.getId(), officer.getId())
                        .ifPresent(memberships::delete);
                memberships.flush();
            }
            case HOLDER_ROLE_NOT_CUSTODY_CAPABLE -> {
                target = evidences.saveAndFlush(evidence(owningCase, auditor, "SEAL.AUDITORHELD"));
            }
        }
    }

    private UUID holderOf(IneligibleHolder cause) {
        return cause == IneligibleHolder.HOLDER_ROLE_NOT_CUSTODY_CAPABLE ? auditor.getId() : officer.getId();
    }

    private EvidenceOperationResponse seal(Operator actor) {
        return authenticated(
                actor, () -> seals.seal(target.getId(), new SealEvidenceRequest(REASON), principal(actor)));
    }

    private void assertUnchangedAndEventFree() {
        DigitalEvidence unchanged = reload();
        assertThat(events.countByEvidenceId(target.getId())).isZero();
        assertThat(unchanged.getStatus()).isEqualTo(EvidenceStatus.IN_CUSTODY);
        assertThat(unchanged.getCustodyEventCount()).isZero();
        assertThat(unchanged.getCustodyChainHeadHash()).isEqualTo(CustodyEventHashing.ZERO_HASH);
        assertThat(unchanged.getUpdatedAt()).isEqualTo(target.getUpdatedAt());
    }

    private EvidenceSealedPayload expectedPayload() {
        return new EvidenceSealedPayload(EvidenceStatus.IN_CUSTODY, EvidenceStatus.SEALED, officer.getId(), REASON);
    }

    private CanonicalCustodyEvent canonical(CustodyEvent event, Operator actor) {
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
                expectedPayload(),
                event.getPreviousHash());
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
