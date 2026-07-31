package it.itsprodigi.proofchain.custodyevent.domain;

import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.EVENT_HASH;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.ZERO_HASH;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.custodyCase;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.event;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.evidence;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.operator;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.payload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class CustodyEventTest {

    private Operator actor;
    private CustodyCase custodyCase;
    private DigitalEvidence evidence;

    @BeforeEach
    void setUp() {
        actor = operator("event-actor", OperatorRole.EVIDENCE_OFFICER);
        custodyCase = custodyCase("Event domain case", actor);
        evidence = evidence(custodyCase, actor, "EVENT-01");
    }

    @Test
    void createsAnImmutableCompleteEventAndSnapshotsPayloadAndActorRole() {
        ObjectNode sourcePayload = (ObjectNode) payload("registered");
        CustodyEvent event = event(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                custodyCase,
                evidence,
                actor,
                OperatorRole.EVIDENCE_OFFICER,
                1,
                EventType.EVIDENCE_REGISTERED,
                Instant.parse("2026-07-29T12:34:56.123456Z"),
                1,
                sourcePayload,
                ZERO_HASH,
                EVENT_HASH,
                1);

        sourcePayload.put("action", "mutated");
        actor.changeRole(OperatorRole.AUDITOR);

        assertThat(event.getId().version()).isEqualTo(4);
        assertThat(event.getCustodyCase()).isSameAs(custodyCase);
        assertThat(event.getEvidence()).isSameAs(evidence);
        assertThat(event.getOperator()).isSameAs(actor);
        assertThat(event.getActorRole()).isEqualTo(OperatorRole.EVIDENCE_OFFICER);
        assertThat(event.getSequenceNumber()).isEqualTo(1);
        assertThat(event.getEventType()).isEqualTo(EventType.EVIDENCE_REGISTERED);
        assertThat(event.getOccurredAt()).isEqualTo(Instant.parse("2026-07-29T12:34:56.123456Z"));
        assertThat(event.getPayloadVersion()).isEqualTo(1);
        assertThat(event.getPayloadJson()).isEqualTo("{\"action\":\"registered\"}");
        assertThat(event.getPreviousHash()).isEqualTo(ZERO_HASH);
        assertThat(event.getEventHash()).isEqualTo(EVENT_HASH);
        assertThat(event.getHashVersion()).isEqualTo(1);
    }

    @Test
    void eventTypeSetIsFrozen() {
        assertThat(EventType.values())
                .containsExactly(
                        EventType.EVIDENCE_REGISTERED,
                        EventType.CUSTODY_TRANSFERRED,
                        EventType.METADATA_UPDATED,
                        EventType.INTEGRITY_VERIFIED,
                        EventType.EVIDENCE_SEALED,
                        EventType.EVIDENCE_RELEASED);
    }

    @Test
    void rejectsInvalidIdentitySequenceTimestampVersionsPayloadAndHashes() {
        assertThatThrownBy(() -> completeEvent(
                        UUID.fromString("11111111-1111-3111-8111-111111111111"),
                        1,
                        Instant.parse("2026-07-29T12:34:56.123456Z"),
                        1,
                        payload("registered"),
                        ZERO_HASH,
                        EVENT_HASH,
                        1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("id must be a UUID v4");
        assertThatThrownBy(() -> completeEvent(
                        UUID.randomUUID(),
                        0,
                        Instant.parse("2026-07-29T12:34:56.123456Z"),
                        1,
                        payload("registered"),
                        ZERO_HASH,
                        EVENT_HASH,
                        1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sequenceNumber must be positive");
        assertThatThrownBy(() -> completeEvent(
                        UUID.randomUUID(),
                        1,
                        Instant.parse("2026-07-29T12:34:56.123456789Z"),
                        1,
                        payload("registered"),
                        ZERO_HASH,
                        EVENT_HASH,
                        1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("occurredAt must have microsecond precision");
        assertThatThrownBy(() -> completeEvent(
                        UUID.randomUUID(),
                        1,
                        Instant.parse("2026-07-29T12:34:56.123456Z"),
                        2,
                        payload("registered"),
                        ZERO_HASH,
                        EVENT_HASH,
                        1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("payloadVersion must be 1");
        assertThatThrownBy(() -> completeEvent(
                        UUID.randomUUID(),
                        1,
                        Instant.parse("2026-07-29T12:34:56.123456Z"),
                        1,
                        tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode(),
                        ZERO_HASH,
                        EVENT_HASH,
                        1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("payloadJson must be an object");
        assertThatThrownBy(() -> completeEvent(
                        UUID.randomUUID(),
                        1,
                        Instant.parse("2026-07-29T12:34:56.123456Z"),
                        1,
                        payload("registered"),
                        "A".repeat(64),
                        EVENT_HASH,
                        1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("previousHash");
        assertThatThrownBy(() -> completeEvent(
                        UUID.randomUUID(),
                        1,
                        Instant.parse("2026-07-29T12:34:56.123456Z"),
                        1,
                        payload("registered"),
                        ZERO_HASH,
                        "short",
                        1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventHash");
        assertThatThrownBy(() -> completeEvent(
                        UUID.randomUUID(),
                        1,
                        Instant.parse("2026-07-29T12:34:56.123456Z"),
                        1,
                        payload("registered"),
                        ZERO_HASH,
                        EVENT_HASH,
                        2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("hashVersion must be 1");
    }

    @Test
    void rejectsCaseEvidenceMismatchAndMissingRequiredReferences() {
        CustodyCase otherCase = custodyCase("Other event case", actor);

        assertThatThrownBy(() -> event(otherCase, evidence, actor, 1, EVENT_HASH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("evidence must belong to custodyCase");
        assertThatThrownBy(() -> event(custodyCase, null, actor, 1, EVENT_HASH))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("evidence must not be null");
        assertThatThrownBy(() -> event(
                        UUID.randomUUID(),
                        custodyCase,
                        evidence,
                        null,
                        OperatorRole.EVIDENCE_OFFICER,
                        1,
                        EventType.EVIDENCE_REGISTERED,
                        Instant.parse("2026-07-29T12:34:56.123456Z"),
                        1,
                        payload("registered"),
                        ZERO_HASH,
                        EVENT_HASH,
                        1))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("operator must not be null");
        assertThatThrownBy(() -> event(
                        UUID.randomUUID(),
                        custodyCase,
                        evidence,
                        actor,
                        OperatorRole.ADMIN,
                        1,
                        EventType.EVIDENCE_REGISTERED,
                        Instant.parse("2026-07-29T12:34:56.123456Z"),
                        1,
                        payload("registered"),
                        ZERO_HASH,
                        EVENT_HASH,
                        1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("actorRole must match operator role at event creation");
    }

    private CustodyEvent completeEvent(
            UUID id,
            long sequenceNumber,
            Instant occurredAt,
            int payloadVersion,
            tools.jackson.databind.JsonNode payloadJson,
            String previousHash,
            String eventHash,
            int hashVersion) {
        return event(
                id,
                custodyCase,
                evidence,
                actor,
                OperatorRole.EVIDENCE_OFFICER,
                sequenceNumber,
                EventType.EVIDENCE_REGISTERED,
                occurredAt,
                payloadVersion,
                payloadJson,
                previousHash,
                eventHash,
                hashVersion);
    }
}
