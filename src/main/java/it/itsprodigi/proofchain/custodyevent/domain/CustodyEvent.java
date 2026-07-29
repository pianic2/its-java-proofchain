package it.itsprodigi.proofchain.custodyevent.domain;

import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

@Entity
@Immutable
@Table(name = "custody_events")
public class CustodyEvent {

    private static final String SHA_256_PATTERN = "[0-9a-f]{64}";
    private static final int CURRENT_VERSION = 1;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "case_id", nullable = false, updatable = false)
    private CustodyCase custodyCase;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "evidence_id", nullable = false, updatable = false)
    private DigitalEvidence evidence;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operator_id", nullable = false, updatable = false)
    private Operator operator;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role", nullable = false, updatable = false, length = 32)
    private OperatorRole actorRole;

    @Positive
    @Column(name = "sequence_number", nullable = false, updatable = false)
    private long sequenceNumber;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 32)
    private EventType eventType;

    @NotNull
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "payload_version", nullable = false, updatable = false)
    private int payloadVersion;

    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payloadJson;

    @Pattern(regexp = SHA_256_PATTERN)
    @Column(name = "previous_hash", nullable = false, updatable = false, length = 64)
    private String previousHash;

    @Pattern(regexp = SHA_256_PATTERN)
    @Column(name = "event_hash", nullable = false, updatable = false, length = 64)
    private String eventHash;

    @Column(name = "hash_version", nullable = false, updatable = false)
    private int hashVersion;

    protected CustodyEvent() {}

    private CustodyEvent(
            UUID id,
            CustodyCase custodyCase,
            DigitalEvidence evidence,
            Operator operator,
            OperatorRole actorRole,
            long sequenceNumber,
            EventType eventType,
            Instant occurredAt,
            int payloadVersion,
            String payloadJson,
            String previousHash,
            String eventHash,
            int hashVersion) {
        this.id = validUuidV4(id);
        this.custodyCase = Objects.requireNonNull(custodyCase, "custodyCase must not be null");
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        if (!custodyCase.getId().equals(evidence.getCustodyCase().getId())) {
            throw new IllegalArgumentException("evidence must belong to custodyCase");
        }
        this.operator = Objects.requireNonNull(operator, "operator must not be null");
        this.actorRole = Objects.requireNonNull(actorRole, "actorRole must not be null");
        if (actorRole != operator.getRole()) {
            throw new IllegalArgumentException("actorRole must match operator role at event creation");
        }
        this.sequenceNumber = validSequenceNumber(sequenceNumber);
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.occurredAt = validOccurredAt(occurredAt);
        this.payloadVersion = validVersion(payloadVersion, "payloadVersion");
        this.payloadJson = validCanonicalPayload(payloadJson);
        this.previousHash = validSha256(previousHash, "previousHash");
        this.eventHash = validSha256(eventHash, "eventHash");
        this.hashVersion = validVersion(hashVersion, "hashVersion");
    }

    static CustodyEvent create(
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
        return new CustodyEvent(
                id,
                custodyCase,
                evidence,
                operator,
                actorRole,
                sequenceNumber,
                eventType,
                occurredAt,
                payloadVersion,
                validPayload(payloadJson),
                previousHash,
                eventHash,
                hashVersion);
    }

    static CustodyEvent createCanonical(
            UUID id,
            CustodyCase custodyCase,
            DigitalEvidence evidence,
            Operator operator,
            OperatorRole actorRole,
            long sequenceNumber,
            EventType eventType,
            Instant occurredAt,
            int payloadVersion,
            String payloadJson,
            String previousHash,
            String eventHash,
            int hashVersion) {
        return new CustodyEvent(
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

    public UUID getId() {
        return id;
    }

    public CustodyCase getCustodyCase() {
        return custodyCase;
    }

    public DigitalEvidence getEvidence() {
        return evidence;
    }

    public Operator getOperator() {
        return operator;
    }

    public OperatorRole getActorRole() {
        return actorRole;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public EventType getEventType() {
        return eventType;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public int getPayloadVersion() {
        return payloadVersion;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public String getPreviousHash() {
        return previousHash;
    }

    public String getEventHash() {
        return eventHash;
    }

    public int getHashVersion() {
        return hashVersion;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CustodyEvent custodyEvent)) {
            return false;
        }
        return id != null && id.equals(custodyEvent.id);
    }

    @Override
    public int hashCode() {
        return CustodyEvent.class.hashCode();
    }

    @Override
    public String toString() {
        return "CustodyEvent{" + "id=" + id + ", sequenceNumber=" + sequenceNumber + ", eventType=" + eventType + '}';
    }

    private static UUID validUuidV4(UUID id) {
        UUID value = Objects.requireNonNull(id, "id must not be null");
        if (value.version() != 4) {
            throw new IllegalArgumentException("id must be a UUID v4");
        }
        return value;
    }

    private static long validSequenceNumber(long sequenceNumber) {
        if (sequenceNumber <= 0) {
            throw new IllegalArgumentException("sequenceNumber must be positive");
        }
        return sequenceNumber;
    }

    private static Instant validOccurredAt(Instant occurredAt) {
        Instant value = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (!value.equals(value.truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalArgumentException("occurredAt must have microsecond precision");
        }
        return value;
    }

    private static int validVersion(int version, String fieldName) {
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException(fieldName + " must be 1");
        }
        return version;
    }

    private static String validPayload(JsonNode payloadJson) {
        JsonNode value = Objects.requireNonNull(payloadJson, "payloadJson must not be null");
        if (!value.isObject()) {
            throw new IllegalArgumentException("payloadJson must be an object");
        }
        return value.toString();
    }

    private static String validCanonicalPayload(String payloadJson) {
        String value = Objects.requireNonNull(payloadJson, "payloadJson must not be null");
        if (value.length() < 2 || value.charAt(0) != '{' || value.charAt(value.length() - 1) != '}') {
            throw new IllegalArgumentException("payloadJson must be a canonical JSON object");
        }
        return value;
    }

    private static String validSha256(String value, String fieldName) {
        String hash = Objects.requireNonNull(value, fieldName + " must not be null");
        if (!hash.matches(SHA_256_PATTERN)) {
            throw new IllegalArgumentException(fieldName + " must be exactly 64 lowercase hexadecimal characters");
        }
        return hash;
    }
}
