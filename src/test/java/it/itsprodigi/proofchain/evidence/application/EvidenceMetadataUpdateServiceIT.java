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
import it.itsprodigi.proofchain.custodyevent.protocol.EvidenceMetadataSnapshot;
import it.itsprodigi.proofchain.custodyevent.protocol.MetadataUpdatedPayload;
import it.itsprodigi.proofchain.evidence.api.EvidenceOperationResponse;
import it.itsprodigi.proofchain.evidence.api.PatchEvidenceMetadataRequest;
import it.itsprodigi.proofchain.evidence.domain.AcquisitionMethod;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.domain.SourceType;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceRepository;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.time.Instant;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL behavior of the descriptive metadata update workflow: the complete before and after snapshots with their
 * explicit nulls, the shared command instant, chain linkage, the no-op conflict, the authorization matrix and atomic
 * rollback at the canonicalization, appender and flush failure points.
 */
class EvidenceMetadataUpdateServiceIT extends PostgreSqlIntegrationTest {

    private static final String REASON = "Corrected the acquisition metadata after the laboratory review.";
    private static final String FIXTURE_TITLE = "Forensic disk image";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private EvidenceMetadataUpdateService metadata;

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
        admin = operators.saveAndFlush(operator("metadata-admin", OperatorRole.ADMIN));
        manager = operators.saveAndFlush(operator("metadata-manager", OperatorRole.CASE_MANAGER));
        officer = operators.saveAndFlush(operator("metadata-officer", OperatorRole.EVIDENCE_OFFICER));
        otherOfficer = operators.saveAndFlush(operator("metadata-other-officer", OperatorRole.EVIDENCE_OFFICER));
        auditor = operators.saveAndFlush(operator("metadata-auditor", OperatorRole.AUDITOR));
        outsider = operators.saveAndFlush(operator("metadata-outsider", OperatorRole.CASE_MANAGER));
        owningCase = custodyCases.saveAndFlush(custodyCase("Metadata case", manager));
        assign(manager, officer, otherOfficer, auditor);
        target = evidences.saveAndFlush(evidence(owningCase, officer, "METADATA"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        cleanDatabaseInDependencyOrder();
    }

    /**
     * One update proves the complete payload contract at once: both snapshots carry the identical fourteen-field set
     * with explicit nulls, the reason lives only outside them, one microsecond instant is shared by the aggregate and
     * the event, and the chain advances by exactly one linked entry.
     */
    @Test
    void appendsOneTypedMetadataUpdatedEventWithCompleteSnapshotsASharedInstantAndChainLinkage() {
        EvidenceOperationResponse first = update(
                manager,
                target.getId(),
                "{\"title\":\"Updated forensic title\",\"acquisitionNotes\":\"Write blocker used.\",\"acquiredAt\":null,\"reason\":\""
                        + REASON + "\"}");

        DigitalEvidence afterFirst = reload();
        CustodyEvent firstEvent = timeline().getFirst();
        EvidenceMetadataSnapshot before = snapshot(FIXTURE_TITLE, null, Instant.EPOCH);
        EvidenceMetadataSnapshot after = snapshot("Updated forensic title", "Write blocker used.", null);

        assertThat(first.eventSummary().eventType()).isEqualTo(EventType.METADATA_UPDATED);
        assertThat(first.eventSummary().sequenceNumber()).isEqualTo(1L);
        assertThat(first.eventSummary().previousHash()).isEqualTo(CustodyEventHashing.ZERO_HASH);
        assertThat(first.evidence().title()).isEqualTo("Updated forensic title");
        assertThat(first.evidence().acquiredAt()).isNull();
        assertThat(first.eventSummary().occurredAt())
                .isEqualTo(first.evidence().updatedAt())
                .isEqualTo(afterFirst.getUpdatedAt())
                .isEqualTo(firstEvent.getOccurredAt())
                .isEqualTo(firstEvent.getOccurredAt().truncatedTo(ChronoUnit.MICROS));
        assertThat(afterFirst.getCustodyEventCount()).isEqualTo(1L);
        assertThat(afterFirst.getCustodyChainHeadHash()).isEqualTo(firstEvent.getEventHash());
        assertThat(JSON.readTree(firstEvent.getPayloadJson()))
                .isEqualTo(JSON.readTree(CustodyEventCanonicalizer.canonicalizePayload(
                        new MetadataUpdatedPayload(before, after, REASON))));
        assertThat(firstEvent.getEventHash())
                .isEqualTo(CustodyEventHashing.eventHash(
                        canonical(firstEvent, manager, new MetadataUpdatedPayload(before, after, REASON))));

        JsonNode payload = JSON.readTree(firstEvent.getPayloadJson());
        assertThat(payload.propertyNames()).containsExactlyInAnyOrder("before", "after", "reason");
        for (String side : new String[] {"before", "after"}) {
            JsonNode snapshot = payload.get(side);
            assertThat(snapshot.size())
                    .as("%s carries the complete field set", side)
                    .isEqualTo(14);
            assertThat(snapshot.has("reason")).isFalse();
            assertThat(snapshot.get("description").isNull()).isTrue();
            assertThat(snapshot.get("sourceManufacturer").isNull()).isTrue();
            assertThat(snapshot.get("acquisitionLocation").isNull()).isTrue();
        }
        assertThat(payload.get("before").get("acquiredAt").isNull()).isFalse();
        assertThat(payload.get("after").get("acquiredAt").isNull()).isTrue();
        assertThat(payload.get("after").get("acquisitionNotes").stringValue()).isEqualTo("Write blocker used.");

        EvidenceOperationResponse second =
                update(manager, target.getId(), "{\"sourceType\":\"CLOUD_SERVICE\",\"reason\":\"" + REASON + "\"}");

        List<CustodyEvent> chain = timeline();
        DigitalEvidence afterSecond = reload();
        assertThat(second.eventSummary().sequenceNumber()).isEqualTo(2L);
        assertThat(chain).extracting(CustodyEvent::getSequenceNumber).containsExactly(1L, 2L);
        assertThat(chain.getLast().getPreviousHash()).isEqualTo(chain.getFirst().getEventHash());
        assertThat(afterSecond.getCustodyChainHeadHash())
                .isEqualTo(chain.getLast().getEventHash());
        assertThat(afterSecond.getSourceType()).isEqualTo(SourceType.CLOUD_SERVICE);
        assertThat(afterSecond.getTitle()).isEqualTo("Updated forensic title");
    }

    @Test
    void refusesANormalizedNoOpWithoutTouchingTheAggregateOrTheChain() {
        DigitalEvidence stored = reload();

        for (String noOp : new String[] {
            "{\"reason\":\"" + REASON + "\"}",
            "{\"title\":\"  " + FIXTURE_TITLE + "  \",\"reason\":\"" + REASON + "\"}",
            "{\"description\":null,\"acquisitionNotes\":\"   \",\"reason\":\"" + REASON + "\"}",
            "{\"acquiredAt\":\"1970-01-01T00:00:00.000000Z\",\"reason\":\"" + REASON + "\"}"
        }) {
            assertThatThrownBy(() -> update(manager, target.getId(), noOp))
                    .isInstanceOf(MetadataUpdateNoOpException.class);
        }

        DigitalEvidence unchanged = reload();
        assertThat(events.countByEvidenceId(target.getId())).isZero();
        assertThat(unchanged.getCustodyEventCount()).isZero();
        assertThat(unchanged.getCustodyChainHeadHash()).isEqualTo(CustodyEventHashing.ZERO_HASH);
        assertThat(unchanged.getUpdatedAt()).isEqualTo(stored.getUpdatedAt());
        assertThat(unchanged.getVersion()).isEqualTo(stored.getVersion());
        assertThat(unchanged.getTitle()).isEqualTo(FIXTURE_TITLE);
    }

    @Test
    void refusesSealedReleasedAndClosedCaseEvidenceWithoutAppendingAnyEvent() {
        DigitalEvidence sealed = evidences.saveAndFlush(evidence(owningCase, officer, "SEALED"));
        sealed.seal();
        UUID sealedId = evidences.saveAndFlush(sealed).getId();
        DigitalEvidence released = evidences.saveAndFlush(evidence(owningCase, officer, "RELEASED"));
        released.release();
        UUID releasedId = evidences.saveAndFlush(released).getId();
        String body = "{\"title\":\"Blocked title\",\"reason\":\"" + REASON + "\"}";

        assertThatThrownBy(() -> update(manager, sealedId, body)).isInstanceOf(InvalidEvidenceStateException.class);
        assertThatThrownBy(() -> update(manager, releasedId, body)).isInstanceOf(InvalidEvidenceStateException.class);
        assertThat(events.countByEvidenceId(sealedId)).isZero();
        assertThat(events.countByEvidenceId(releasedId)).isZero();

        owningCase.close();
        custodyCases.saveAndFlush(owningCase);

        assertThatThrownBy(() -> update(manager, target.getId(), body)).isInstanceOf(CaseClosedException.class);
        assertThat(events.countByEvidenceId(target.getId())).isZero();
        assertThat(reload().getTitle()).isEqualTo(FIXTURE_TITLE);
    }

    /**
     * Caller role and case membership completely determine the outcome. Unlike a custody transfer, a member
     * {@code EVIDENCE_OFFICER} does not have to be the current holder to correct descriptive metadata.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Caller.class)
    void enforcesTheCompleteRoleAndMembershipMatrix(Caller caller) {
        Operator actor = callerOperator(caller);
        ThrowingCallable command =
                () -> update(actor, target.getId(), "{\"title\":\"Matrix title\",\"reason\":\"" + REASON + "\"}");

        switch (caller.outcome) {
            case ALLOWED -> {
                assertThatCode(command).doesNotThrowAnyException();
                assertThat(reload().getTitle()).isEqualTo("Matrix title");
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
        String body = "{\"title\":\"Hidden title\",\"reason\":\"" + REASON + "\"}";

        ResourceNotFoundException hidden = notFound(() -> update(outsider, target.getId(), body));
        ResourceNotFoundException missing = notFound(() -> update(outsider, UUID.randomUUID(), body));

        assertThat(hidden).hasSameClassAs(missing).hasMessage(missing.getMessage());
        assertThat(events.countByEvidenceId(target.getId())).isZero();
    }

    /**
     * The canonicalization row needs no mock: a reason carrying an unpaired surrogate is accepted by the reason
     * contract but cannot be canonicalized, so the failure happens between the aggregate mutation and the custody event
     * insert, exactly where atomicity must hold.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(WriteFailure.class)
    void rollsBackTheMetadataMutationAndTheEventTogether(WriteFailure failure) {
        DigitalEvidence stored = reload();
        switch (failure) {
            case CANONICALIZATION -> {
                // the unpaired surrogate in the reason below is the trigger; no stubbing is required
            }
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
        String reason = failure == WriteFailure.CANONICALIZATION ? "\\ud800" : REASON;

        assertThatThrownBy(() ->
                        update(manager, target.getId(), "{\"title\":\"Rolled back\",\"reason\":\"" + reason + "\"}"))
                .isInstanceOf(failure.expected);

        DigitalEvidence unchanged = reload();
        assertThat(events.countByEvidenceId(target.getId())).isZero();
        assertThat(unchanged.getCustodyEventCount()).isZero();
        assertThat(unchanged.getCustodyChainHeadHash()).isEqualTo(CustodyEventHashing.ZERO_HASH);
        assertThat(unchanged.getTitle()).isEqualTo(FIXTURE_TITLE);
        assertThat(unchanged.getUpdatedAt()).isEqualTo(stored.getUpdatedAt());

        reset(events, evidences);
        assertThat(update(manager, target.getId(), "{\"title\":\"Recovered\",\"reason\":\"" + REASON + "\"}")
                        .eventSummary()
                        .sequenceNumber())
                .isEqualTo(1L);
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
        MEMBER_EVIDENCE_OFFICER_NON_HOLDER(Outcome.ALLOWED),
        MEMBER_AUDITOR(Outcome.FORBIDDEN),
        NON_MEMBER_CASE_MANAGER(Outcome.NOT_FOUND);

        private final Outcome outcome;

        Caller(Outcome outcome) {
            this.outcome = outcome;
        }
    }

    private enum WriteFailure {
        CANONICALIZATION(IllegalArgumentException.class),
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

    private static EvidenceMetadataSnapshot snapshot(String title, String acquisitionNotes, Instant acquiredAt) {
        return new EvidenceMetadataSnapshot(
                title,
                null,
                SourceType.DEVICE,
                null,
                null,
                null,
                null,
                null,
                AcquisitionMethod.PHYSICAL,
                acquiredAt,
                null,
                null,
                null,
                acquisitionNotes);
    }

    private EvidenceOperationResponse update(Operator actor, UUID evidenceId, String json) {
        PatchEvidenceMetadataRequest request = JSON.readValue(json, PatchEvidenceMetadataRequest.class);
        return authenticated(actor, () -> metadata.update(evidenceId, request, principal(actor)));
    }

    private List<CustodyEvent> timeline() {
        return events.findAllByEvidenceIdOrderBySequenceNumberAsc(target.getId());
    }

    private DigitalEvidence reload() {
        return evidences.findByIdForVisibility(target.getId()).orElseThrow();
    }

    private CanonicalCustodyEvent canonical(CustodyEvent event, Operator actor, MetadataUpdatedPayload payload) {
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
