package it.itsprodigi.proofchain.evidence.application;

import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.custodyCase;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.evidence;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.operator;
import static org.assertj.core.api.Assertions.assertThat;

import it.itsprodigi.proofchain.custodyevent.application.CustodyEventAppendResult;
import it.itsprodigi.proofchain.custodyevent.domain.EventType;
import it.itsprodigi.proofchain.evidence.api.EvidenceOperationResponse;
import it.itsprodigi.proofchain.evidence.api.EvidenceResponse;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvidenceCommandResponseMapperTest {

    private final EvidenceCommandResponseMapper mapper = new EvidenceCommandResponseMapper(new EvidenceMapper());

    @Test
    void mapsTheAggregateAndTheAppendedEventIntoTheSharedContractWithItsLocation() {
        Operator actor = operator("mapper-actor", OperatorRole.CASE_MANAGER);
        DigitalEvidence target = evidence(custodyCase("Mapper case", actor), actor, "MAP");
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-07-31T10:15:30.123456Z");
        CustodyEventAppendResult appended = new CustodyEventAppendResult(
                eventId, 2L, EventType.CUSTODY_TRANSFERRED, occurredAt, "0".repeat(64), "a".repeat(64), 1, 1);

        EvidenceOperationResponse response = mapper.toResponse(target, actor, appended);

        assertThat(response.evidence().id()).isEqualTo(target.getId());
        assertThat(response.evidence().caseId())
                .isEqualTo(target.getCustodyCase().getId());
        assertThat(response.eventSummary().id()).isEqualTo(eventId);
        assertThat(response.eventSummary().evidenceId()).isEqualTo(target.getId());
        assertThat(response.eventSummary().caseId())
                .isEqualTo(target.getCustodyCase().getId());
        assertThat(response.eventSummary().sequenceNumber()).isEqualTo(2L);
        assertThat(response.eventSummary().eventType()).isEqualTo(EventType.CUSTODY_TRANSFERRED);
        assertThat(response.eventSummary().operatorId()).isEqualTo(actor.getId());
        assertThat(response.eventSummary().actorRole()).isEqualTo(actor.getRole());
        assertThat(response.eventSummary().occurredAt()).isEqualTo(occurredAt);
        assertThat(response.eventSummary().previousHash()).isEqualTo("0".repeat(64));
        assertThat(response.eventSummary().eventHash()).isEqualTo("a".repeat(64));
        assertThat(response.eventSummary().hashVersion()).isEqualTo(1);
        assertThat(response.eventSummary().payloadVersion()).isEqualTo(1);
        assertThat(mapper.location(response)).hasToString("/api/v1/evidences/" + target.getId() + "/events/" + eventId);
    }

    @Test
    void responseNeverExposesVersionStorageOrChainHeadFields() {
        assertThat(componentNames(EvidenceOperationResponse.class)).containsExactly("evidence", "eventSummary");
        assertThat(componentNames(EvidenceResponse.class))
                .doesNotContain("version", "storageKey", "custodyChainHeadHash", "custodyEventCount");
    }

    private static java.util.List<String> componentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }
}
