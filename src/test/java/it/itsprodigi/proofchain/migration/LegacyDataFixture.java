package it.itsprodigi.proofchain.migration;

import it.itsprodigi.proofchain.custodyevent.protocol.EvidenceRegisteredPayload;
import it.itsprodigi.proofchain.evidence.domain.AcquisitionMethod;
import it.itsprodigi.proofchain.evidence.domain.EvidenceStatus;
import it.itsprodigi.proofchain.evidence.domain.SourceType;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Representative rows for a reconstructed baseline, inserted at the point of the historical timeline where the tables
 * they use actually existed. Values are fixed so preservation can be asserted column by column after an upgrade.
 */
final class LegacyDataFixture {

    static final UUID ADMIN_ID = UUID.fromString("90000000-0000-4000-8000-000000000001");
    static final UUID OFFICER_ID = UUID.fromString("90000000-0000-4000-8000-000000000002");
    static final UUID CASE_ID = UUID.fromString("91000000-0000-4000-8000-000000000001");
    static final UUID MEMBERSHIP_ID = UUID.fromString("92000000-0000-4000-8000-000000000001");
    static final UUID EVIDENCE_ID = UUID.fromString("93000000-0000-4000-8000-000000000001");

    static final Instant CREATED_AT = Instant.parse("2026-03-04T08:09:10.123456Z");
    static final Instant ACQUIRED_AT = Instant.parse("2026-03-04T07:00:00.654321Z");

    static final String CONTENT_SHA256 = "1".repeat(64);
    static final String CONTEXTUAL_SHA256 = "2".repeat(64);
    static final String BCRYPT_HASH = "$2a$10$01234567890123456789012345678901234567890123456789012";

    static final String REFERENCE_TAG = "LEGACY-01";

    /**
     * A title PostgreSQL accepts and the custody-event protocol refuses. {@code btrim} only strips ASCII spaces, so the
     * V3 check constraint sees three well-formed characters, while {@link String#strip()} removes the U+2000 EN QUAD
     * and leaves two — below the protocol minimum. Ambiguous legacy text like this must fail the migration instead of
     * being silently normalized.
     */
    static final String TITLE_REJECTED_BY_THE_PROTOCOL = "\u2000ab";

    static final String TITLE = "Legacy disk image";
    static final String DESCRIPTION = "Representative evidence row created before the custody event chain existed";
    static final String ORIGINAL_FILENAME = "legacy-disk.E01";
    static final long FILE_SIZE = 8192L;

    private LegacyDataFixture() {}

    static void insertOperators(MigrationSchemaHarness harness) {
        insertOperator(harness, ADMIN_ID, "legacy.admin", OperatorRole.ADMIN);
        insertOperator(harness, OFFICER_ID, "legacy.officer", OperatorRole.EVIDENCE_OFFICER);
    }

    static void insertCaseAndMembership(MigrationSchemaHarness harness) {
        harness.execute(
                """
                INSERT INTO custody_cases (
                    id, title, description, authority_name, external_reference, location,
                    priority, status, created_by_operator_id, created_at, updated_at, closed_at, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 0)
                """,
                CASE_ID,
                "Legacy custody case",
                "Representative case row created before the custody event chain existed",
                "ProofChain Lab",
                "LEGACY-CASE-01",
                "Rome",
                "HIGH",
                "OPEN",
                ADMIN_ID,
                utc(CREATED_AT),
                utc(CREATED_AT));
        harness.execute("""
                INSERT INTO case_memberships (id, case_id, operator_id, assigned_by_operator_id, assigned_at)
                VALUES (?, ?, ?, ?, ?)
                """, MEMBERSHIP_ID, CASE_ID, OFFICER_ID, ADMIN_ID, utc(CREATED_AT));
    }

    static void insertEvidence(MigrationSchemaHarness harness, EvidenceSeed seed) {
        harness.execute(
                """
                INSERT INTO digital_evidence (
                    id, case_id, reference_tag, title, description, status,
                    current_holder_operator_id, uploaded_by_operator_id,
                    source_type, source_description, source_manufacturer, source_model,
                    source_serial_number, source_logical_identifier, acquisition_method,
                    acquisition_location, acquisition_tool_name, acquisition_tool_version,
                    acquisition_notes, acquired_at, original_filename, file_extension,
                    media_type, file_size, content_sha256, contextual_sha256, storage_key,
                    created_at, updated_at, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """,
                seed.id(),
                CASE_ID,
                REFERENCE_TAG,
                seed.title(),
                DESCRIPTION,
                seed.status(),
                seed.holderId(),
                OFFICER_ID,
                SourceType.DEVICE.name(),
                "Seized workstation SSD",
                "Acme",
                "Forensic One",
                "SN-LEGACY-01",
                "/dev/nvme0n1",
                AcquisitionMethod.PHYSICAL.name(),
                "ProofChain Lab",
                "Forensic Imager",
                "3.0",
                "Write blocker used",
                utc(ACQUIRED_AT),
                ORIGINAL_FILENAME,
                "e01",
                "application/octet-stream",
                FILE_SIZE,
                CONTENT_SHA256,
                CONTEXTUAL_SHA256,
                "cases/" + CASE_ID + "/evidences/" + seed.id() + "/content.bin",
                utc(CREATED_AT),
                utc(CREATED_AT));
    }

    /** The payload the V6 backfill must derive from the standard evidence row — never guessed, always derived. */
    static EvidenceRegisteredPayload expectedGenesisPayload() {
        return new EvidenceRegisteredPayload(
                true,
                REFERENCE_TAG,
                TITLE,
                DESCRIPTION,
                EvidenceStatus.IN_CUSTODY,
                SourceType.DEVICE,
                "Seized workstation SSD",
                "Acme",
                "Forensic One",
                "SN-LEGACY-01",
                "/dev/nvme0n1",
                AcquisitionMethod.PHYSICAL,
                ACQUIRED_AT,
                "ProofChain Lab",
                "Forensic Imager",
                "3.0",
                "Write blocker used",
                ORIGINAL_FILENAME,
                "e01",
                "application/octet-stream",
                FILE_SIZE,
                CONTENT_SHA256,
                CONTEXTUAL_SHA256,
                OFFICER_ID,
                OFFICER_ID);
    }

    static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void insertOperator(MigrationSchemaHarness harness, UUID id, String username, OperatorRole role) {
        harness.execute(
                """
                INSERT INTO operators (
                    id, username, email, password_hash, first_name, last_name, role, status,
                    created_at, updated_at, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, 0)
                """,
                id,
                username,
                username + "@example.test",
                BCRYPT_HASH,
                "Legacy",
                role == OperatorRole.ADMIN ? "Administrator" : "Officer",
                role.name(),
                utc(CREATED_AT),
                utc(CREATED_AT));
    }

    /** The evidence row variations the certification needs: the consistent one and the inconsistent legacy ones. */
    record EvidenceSeed(UUID id, String title, String status, UUID holderId) {

        static EvidenceSeed consistent() {
            return new EvidenceSeed(EVIDENCE_ID, TITLE, EvidenceStatus.IN_CUSTODY.name(), OFFICER_ID);
        }

        EvidenceSeed withTitle(String newTitle) {
            return new EvidenceSeed(id, newTitle, status, holderId);
        }

        EvidenceSeed withStatus(String newStatus) {
            return new EvidenceSeed(id, title, newStatus, holderId);
        }

        EvidenceSeed released() {
            return new EvidenceSeed(id, title, EvidenceStatus.RELEASED.name(), null);
        }
    }
}
