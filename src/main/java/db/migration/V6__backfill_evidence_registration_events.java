package db.migration;

import it.itsprodigi.proofchain.custodyevent.domain.EventType;
import it.itsprodigi.proofchain.custodyevent.protocol.CanonicalCustodyEvent;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventCanonicalizer;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventHashing;
import it.itsprodigi.proofchain.custodyevent.protocol.EvidenceRegisteredPayload;
import it.itsprodigi.proofchain.evidence.domain.AcquisitionMethod;
import it.itsprodigi.proofchain.evidence.domain.EvidenceStatus;
import it.itsprodigi.proofchain.evidence.domain.SourceType;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public final class V6__backfill_evidence_registration_events extends BaseJavaMigration {

    private static final String SELECT_EVIDENCE = """
            SELECT evidence.id,
                   evidence.case_id,
                   evidence.reference_tag,
                   evidence.title,
                   evidence.description,
                   evidence.status,
                   evidence.current_holder_operator_id,
                   evidence.uploaded_by_operator_id,
                   evidence.source_type,
                   evidence.source_description,
                   evidence.source_manufacturer,
                   evidence.source_model,
                   evidence.source_serial_number,
                   evidence.source_logical_identifier,
                   evidence.acquisition_method,
                   evidence.acquired_at,
                   evidence.acquisition_location,
                   evidence.acquisition_tool_name,
                   evidence.acquisition_tool_version,
                   evidence.acquisition_notes,
                   evidence.original_filename,
                   evidence.file_extension,
                   evidence.media_type,
                   evidence.file_size,
                   evidence.content_sha256,
                   evidence.contextual_sha256,
                   evidence.created_at,
                   evidence.custody_event_count,
                   evidence.custody_chain_head_hash,
                   custody_case.id AS resolved_case_id,
                   uploader.id AS resolved_uploader_id,
                   uploader.role AS uploader_role,
                   holder.id AS resolved_holder_id
            FROM digital_evidence evidence
            LEFT JOIN custody_cases custody_case ON custody_case.id = evidence.case_id
            LEFT JOIN operators uploader ON uploader.id = evidence.uploaded_by_operator_id
            LEFT JOIN operators holder ON holder.id = evidence.current_holder_operator_id
            ORDER BY evidence.id
            FOR UPDATE OF evidence
            """;

    private static final String SELECT_EVENTS = """
            SELECT id,
                   case_id,
                   evidence_id,
                   operator_id,
                   actor_role,
                   sequence_number,
                   event_type,
                   occurred_at,
                   payload_version,
                   previous_hash,
                   event_hash,
                   hash_version
            FROM custody_events
            WHERE evidence_id = ?
            ORDER BY sequence_number, id
            """;

    private static final String INSERT_EVENT = """
            INSERT INTO custody_events (
                id,
                case_id,
                evidence_id,
                operator_id,
                actor_role,
                sequence_number,
                event_type,
                occurred_at,
                payload_version,
                payload_json,
                previous_hash,
                event_hash,
                hash_version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
            """;

    private static final String UPDATE_CHAIN_HEAD = """
            UPDATE digital_evidence
            SET custody_event_count = 1,
                custody_chain_head_hash = ?
            WHERE id = ?
              AND custody_event_count = 0
              AND custody_chain_head_hash = ?
            """;

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        for (EvidenceRow evidence : loadEvidence(connection)) {
            backfillOrValidate(connection, evidence);
        }
    }

    private static List<EvidenceRow> loadEvidence(Connection connection) throws SQLException {
        List<EvidenceRow> evidence = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(SELECT_EVIDENCE);
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                evidence.add(readEvidence(resultSet));
            }
        }
        return List.copyOf(evidence);
    }

    private static EvidenceRow readEvidence(ResultSet resultSet) throws SQLException {
        return new EvidenceRow(
                uuid(resultSet, "id"),
                uuid(resultSet, "case_id"),
                resultSet.getString("reference_tag"),
                resultSet.getString("title"),
                resultSet.getString("description"),
                resultSet.getString("status"),
                uuid(resultSet, "current_holder_operator_id"),
                uuid(resultSet, "uploaded_by_operator_id"),
                resultSet.getString("source_type"),
                resultSet.getString("source_description"),
                resultSet.getString("source_manufacturer"),
                resultSet.getString("source_model"),
                resultSet.getString("source_serial_number"),
                resultSet.getString("source_logical_identifier"),
                resultSet.getString("acquisition_method"),
                instant(resultSet, "acquired_at"),
                resultSet.getString("acquisition_location"),
                resultSet.getString("acquisition_tool_name"),
                resultSet.getString("acquisition_tool_version"),
                resultSet.getString("acquisition_notes"),
                resultSet.getString("original_filename"),
                resultSet.getString("file_extension"),
                resultSet.getString("media_type"),
                resultSet.getLong("file_size"),
                resultSet.getString("content_sha256"),
                resultSet.getString("contextual_sha256"),
                instant(resultSet, "created_at"),
                resultSet.getLong("custody_event_count"),
                resultSet.getString("custody_chain_head_hash"),
                uuid(resultSet, "resolved_case_id"),
                uuid(resultSet, "resolved_uploader_id"),
                resultSet.getString("uploader_role"),
                uuid(resultSet, "resolved_holder_id"));
    }

    private static void backfillOrValidate(Connection connection, EvidenceRow evidence) throws SQLException {
        BackfillProtocol protocol = protocol(evidence);
        List<StoredEvent> storedEvents = loadEvents(connection, evidence.id());
        if (evidence.eventCount() == 0) {
            if (!CustodyEventHashing.ZERO_HASH.equals(evidence.chainHead()) || !storedEvents.isEmpty()) {
                throw inconsistent(evidence.id(), "empty-chain-mismatch");
            }
            CanonicalCustodyEvent event = protocol.newGenesis();
            String eventHash = CustodyEventHashing.eventHash(event);
            insertEvent(connection, event, eventHash);
            try (PreparedStatement update = connection.prepareStatement(UPDATE_CHAIN_HEAD)) {
                update.setString(1, eventHash);
                update.setObject(2, evidence.id());
                update.setString(3, CustodyEventHashing.ZERO_HASH);
                if (update.executeUpdate() != 1) {
                    throw inconsistent(evidence.id(), "chain-head-concurrent-change");
                }
            }
            return;
        }
        if (evidence.eventCount() != 1 || storedEvents.size() != 1) {
            throw inconsistent(evidence.id(), "count-event-mismatch");
        }
        validateExisting(connection, evidence, protocol, storedEvents.getFirst());
    }

    private static BackfillProtocol protocol(EvidenceRow evidence) {
        try {
            if (evidence.resolvedCaseId() == null || !evidence.caseId().equals(evidence.resolvedCaseId())) {
                throw inconsistent(evidence.id(), "missing-case-reference");
            }
            if (evidence.resolvedUploaderId() == null
                    || !evidence.uploadedById().equals(evidence.resolvedUploaderId())) {
                throw inconsistent(evidence.id(), "missing-uploader-reference");
            }
            if (evidence.currentHolderId() == null
                    || evidence.resolvedHolderId() == null
                    || !evidence.currentHolderId().equals(evidence.resolvedHolderId())) {
                throw inconsistent(evidence.id(), "missing-holder-reference");
            }
            EvidenceStatus status = EvidenceStatus.valueOf(evidence.status());
            if (status != EvidenceStatus.IN_CUSTODY) {
                throw inconsistent(evidence.id(), "unsupported-evidence-status");
            }
            OperatorRole actorRole = OperatorRole.valueOf(evidence.uploaderRole());
            EvidenceRegisteredPayload payload = new EvidenceRegisteredPayload(
                    true,
                    evidence.referenceTag(),
                    evidence.title(),
                    evidence.description(),
                    status,
                    SourceType.valueOf(evidence.sourceType()),
                    evidence.sourceDescription(),
                    evidence.sourceManufacturer(),
                    evidence.sourceModel(),
                    evidence.sourceSerialNumber(),
                    evidence.sourceLogicalIdentifier(),
                    AcquisitionMethod.valueOf(evidence.acquisitionMethod()),
                    evidence.acquiredAt(),
                    evidence.acquisitionLocation(),
                    evidence.acquisitionToolName(),
                    evidence.acquisitionToolVersion(),
                    evidence.acquisitionNotes(),
                    evidence.originalFilename(),
                    evidence.fileExtension(),
                    evidence.mediaType(),
                    evidence.fileSize(),
                    evidence.contentSha256(),
                    evidence.contextualSha256(),
                    evidence.uploadedById(),
                    evidence.currentHolderId());
            return new BackfillProtocol(evidence, actorRole, payload);
        } catch (FlywayException exception) {
            throw exception;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw inconsistent(evidence.id(), "malformed-evidence-snapshot");
        }
    }

    private static List<StoredEvent> loadEvents(Connection connection, UUID evidenceId) throws SQLException {
        List<StoredEvent> events = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(SELECT_EVENTS)) {
            statement.setObject(1, evidenceId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    events.add(new StoredEvent(
                            uuid(resultSet, "id"),
                            uuid(resultSet, "case_id"),
                            uuid(resultSet, "evidence_id"),
                            uuid(resultSet, "operator_id"),
                            resultSet.getString("actor_role"),
                            resultSet.getLong("sequence_number"),
                            resultSet.getString("event_type"),
                            instant(resultSet, "occurred_at"),
                            resultSet.getInt("payload_version"),
                            resultSet.getString("previous_hash"),
                            resultSet.getString("event_hash"),
                            resultSet.getInt("hash_version")));
                }
            }
        }
        return List.copyOf(events);
    }

    private static void insertEvent(Connection connection, CanonicalCustodyEvent event, String eventHash)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(INSERT_EVENT)) {
            insert.setObject(1, event.eventId());
            insert.setObject(2, event.caseId());
            insert.setObject(3, event.evidenceId());
            insert.setObject(4, event.operatorId());
            insert.setString(5, event.actorRole().name());
            insert.setLong(6, event.sequenceNumber());
            insert.setString(7, event.eventType().name());
            insert.setObject(8, OffsetDateTime.ofInstant(event.occurredAt(), java.time.ZoneOffset.UTC));
            insert.setInt(9, event.payloadVersion());
            insert.setString(10, CustodyEventCanonicalizer.canonicalizePayload(event.payload()));
            insert.setString(11, event.previousHash());
            insert.setString(12, eventHash);
            insert.setInt(13, CustodyEventHashing.HASH_VERSION);
            if (insert.executeUpdate() != 1) {
                throw inconsistent(event.evidenceId(), "event-insert-failed");
            }
        }
    }

    private static void validateExisting(
            Connection connection, EvidenceRow evidence, BackfillProtocol protocol, StoredEvent stored)
            throws SQLException {
        CanonicalCustodyEvent expected;
        try {
            expected = protocol.existing(stored.id(), stored.occurredAt());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw inconsistent(evidence.id(), "malformed-existing-event");
        }
        String expectedHash = CustodyEventHashing.eventHash(expected);
        boolean exactEnvelope = evidence.caseId().equals(stored.caseId())
                && evidence.id().equals(stored.evidenceId())
                && evidence.uploadedById().equals(stored.operatorId())
                && protocol.actorRole().name().equals(stored.actorRole())
                && stored.sequenceNumber() == 1
                && EventType.EVIDENCE_REGISTERED.name().equals(stored.eventType())
                && evidence.createdAt().equals(stored.occurredAt())
                && stored.payloadVersion() == CanonicalCustodyEvent.PAYLOAD_VERSION
                && CustodyEventHashing.ZERO_HASH.equals(stored.previousHash())
                && expectedHash.equals(stored.eventHash())
                && stored.hashVersion() == CustodyEventHashing.HASH_VERSION
                && expectedHash.equals(evidence.chainHead());
        if (!exactEnvelope || !payloadMatches(connection, stored.id(), protocol.payload())) {
            throw inconsistent(evidence.id(), "existing-backfill-mismatch");
        }
    }

    private static boolean payloadMatches(Connection connection, UUID eventId, EvidenceRegisteredPayload payload)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT payload_json = CAST(? AS jsonb) FROM custody_events WHERE id = ?")) {
            statement.setString(1, CustodyEventCanonicalizer.canonicalizePayload(payload));
            statement.setObject(2, eventId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getBoolean(1);
            }
        }
    }

    private static UUID uuid(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, UUID.class);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static FlywayException inconsistent(UUID evidenceId, String reason) {
        return new FlywayException("Custody event backfill rejected evidenceId=" + evidenceId + " reason=" + reason);
    }

    private record BackfillProtocol(EvidenceRow evidence, OperatorRole actorRole, EvidenceRegisteredPayload payload) {

        CanonicalCustodyEvent newGenesis() {
            return canonical(UUID.randomUUID(), evidence.createdAt());
        }

        CanonicalCustodyEvent existing(UUID eventId, Instant occurredAt) {
            return canonical(eventId, occurredAt);
        }

        private CanonicalCustodyEvent canonical(UUID eventId, Instant occurredAt) {
            return new CanonicalCustodyEvent(
                    eventId,
                    evidence.caseId(),
                    evidence.id(),
                    evidence.uploadedById(),
                    actorRole,
                    1,
                    EventType.EVIDENCE_REGISTERED,
                    occurredAt,
                    CanonicalCustodyEvent.PAYLOAD_VERSION,
                    payload,
                    CustodyEventHashing.ZERO_HASH);
        }
    }

    private record StoredEvent(
            UUID id,
            UUID caseId,
            UUID evidenceId,
            UUID operatorId,
            String actorRole,
            long sequenceNumber,
            String eventType,
            Instant occurredAt,
            int payloadVersion,
            String previousHash,
            String eventHash,
            int hashVersion) {}

    private record EvidenceRow(
            UUID id,
            UUID caseId,
            String referenceTag,
            String title,
            String description,
            String status,
            UUID currentHolderId,
            UUID uploadedById,
            String sourceType,
            String sourceDescription,
            String sourceManufacturer,
            String sourceModel,
            String sourceSerialNumber,
            String sourceLogicalIdentifier,
            String acquisitionMethod,
            Instant acquiredAt,
            String acquisitionLocation,
            String acquisitionToolName,
            String acquisitionToolVersion,
            String acquisitionNotes,
            String originalFilename,
            String fileExtension,
            String mediaType,
            long fileSize,
            String contentSha256,
            String contextualSha256,
            Instant createdAt,
            long eventCount,
            String chainHead,
            UUID resolvedCaseId,
            UUID resolvedUploaderId,
            String uploaderRole,
            UUID resolvedHolderId) {

        EvidenceRow {
            Objects.requireNonNull(id, "id must not be null");
        }
    }
}
