package it.itsprodigi.proofchain.custodyevent.domain;

import it.itsprodigi.proofchain.custodycase.domain.CasePriority;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.evidence.domain.AcquisitionMethod;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.domain.SourceType;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

public final class CustodyEventFixtures {

    public static final String BCRYPT_HASH = "$2a$10$01234567890123456789012345678901234567890123456789012";
    public static final String ZERO_HASH = "0".repeat(64);
    public static final String EVENT_HASH = "a".repeat(64);

    private CustodyEventFixtures() {}

    public static Operator operator(String username, OperatorRole role) {
        return Operator.create(username, username + "@example.com", BCRYPT_HASH, "Jane", "Doe", role);
    }

    public static CustodyCase custodyCase(String title, Operator creator) {
        return CustodyCase.create(title, null, null, null, null, CasePriority.MEDIUM, creator);
    }

    public static DigitalEvidence evidence(CustodyCase custodyCase, Operator operator, String referenceTag) {
        return DigitalEvidence.create(
                custodyCase,
                operator,
                operator,
                referenceTag,
                "Forensic disk image",
                null,
                SourceType.DEVICE,
                null,
                null,
                null,
                null,
                null,
                AcquisitionMethod.PHYSICAL,
                null,
                null,
                null,
                null,
                Instant.EPOCH,
                "disk-image.E01",
                "application/octet-stream",
                4096L,
                "b".repeat(64),
                "c".repeat(64),
                "cases/case-id/evidences/evidence-id/content.bin");
    }

    public static JsonNode payload(String action) {
        return JsonNodeFactory.instance.objectNode().put("action", action);
    }

    public static CustodyEvent event(
            CustodyCase custodyCase,
            DigitalEvidence evidence,
            Operator operator,
            long sequenceNumber,
            String eventHash) {
        return event(
                UUID.randomUUID(),
                custodyCase,
                evidence,
                operator,
                operator.getRole(),
                sequenceNumber,
                EventType.EVIDENCE_REGISTERED,
                Instant.parse("2026-07-29T12:34:56.123456Z"),
                1,
                payload("registered"),
                ZERO_HASH,
                eventHash,
                1);
    }

    public static CustodyEvent event(
            UUID id,
            CustodyCase custodyCase,
            DigitalEvidence evidence,
            Operator operator,
            OperatorRole actorRole,
            long sequenceNumber,
            EventType eventType,
            Instant occurredAt,
            int payloadVersion,
            JsonNode payloadJson,
            String previousHash,
            String eventHash,
            int hashVersion) {
        return CustodyEvent.create(
                id,
                custodyCase,
                evidence,
                operator,
                actorRole,
                sequenceNumber,
                eventType,
                occurredAt,
                payloadVersion,
                payloadJson,
                previousHash,
                eventHash,
                hashVersion);
    }
}
