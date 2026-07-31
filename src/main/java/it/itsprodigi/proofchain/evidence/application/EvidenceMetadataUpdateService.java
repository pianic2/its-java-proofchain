package it.itsprodigi.proofchain.evidence.application;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.EvidenceMetadataSnapshot;
import it.itsprodigi.proofchain.custodyevent.protocol.MetadataUpdatedPayload;
import it.itsprodigi.proofchain.evidence.api.EvidenceOperationResponse;
import it.itsprodigi.proofchain.evidence.api.PatchEvidenceMetadataRequest;
import it.itsprodigi.proofchain.evidence.api.PatchEvidenceMetadataRequest.Field;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.domain.EvidenceStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Descriptive metadata update workflow built on the shared Sprint 5 operational command foundation.
 *
 * <p>The service owns only the workflow-specific part: the {@code IN_CUSTODY} gate, the presence-aware merge, the
 * complete before and after snapshots, the no-op guard, the aggregate mutation and the frozen Sprint 4
 * {@code METADATA_UPDATED} payload. Visibility, method security, authorization, the frozen lock order, the single
 * command instant shared by {@code updatedAt} and {@code occurredAt}, conflict translation, sanitized operational
 * logging and response mapping are all reused from {@link EvidenceOperationalCommandService}.
 *
 * <p>Snapshots are always built from the trusted locked aggregate, never from the request or from a generic
 * serialization of the entity, and they always contain the identical complete field set including explicit nulls. The
 * operational reason is validated once, carried into the payload and never logged.
 */
@Service
public class EvidenceMetadataUpdateService {

    private final EvidenceOperationalCommandService commands;

    public EvidenceMetadataUpdateService(EvidenceOperationalCommandService commands) {
        this.commands = Objects.requireNonNull(commands, "commands must not be null");
    }

    @PreAuthorize("isAuthenticated()")
    public EvidenceOperationResponse update(
            UUID evidenceId, PatchEvidenceMetadataRequest request, AuthenticatedOperator actor) {
        Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        if (request == null) {
            throw new EvidenceRequestValidationException();
        }
        String reason = EvidenceCommandReason.require(request.getReason());
        return commands.execute(
                EvidenceOperationalCommand.METADATA_UPDATE,
                evidenceId,
                actor,
                context -> apply(context, request, reason));
    }

    /**
     * Workflow body executed with the custody case read lock and the evidence write lock already held, after shared
     * authorization and case status checks and after the single command instant has been generated.
     */
    private static CustodyEventPayload apply(
            EvidenceCommandContext context, PatchEvidenceMetadataRequest request, String reason) {
        DigitalEvidence evidence = context.evidence();
        if (evidence.getStatus() != EvidenceStatus.IN_CUSTODY) {
            throw new InvalidEvidenceStateException("Only evidence in custody can change descriptive metadata.");
        }
        EvidenceMetadataSnapshot before = snapshot(evidence);
        EvidenceMetadataSnapshot requested = merge(before, request, evidence.getCreatedAt());
        if (requested.equals(before)) {
            throw new MetadataUpdateNoOpException();
        }
        applyTo(evidence, requested);
        return new MetadataUpdatedPayload(before, snapshot(evidence), reason);
    }

    /** Complete normalized snapshot of every modifiable field of the locked aggregate, explicit nulls included. */
    private static EvidenceMetadataSnapshot snapshot(DigitalEvidence evidence) {
        return new EvidenceMetadataSnapshot(
                evidence.getTitle(),
                evidence.getDescription(),
                evidence.getSourceType(),
                evidence.getSourceDescription(),
                evidence.getSourceManufacturer(),
                evidence.getSourceModel(),
                evidence.getSourceSerialNumber(),
                evidence.getSourceLogicalIdentifier(),
                evidence.getAcquisitionMethod(),
                evidence.getAcquiredAt(),
                evidence.getAcquisitionLocation(),
                evidence.getAcquisitionToolName(),
                evidence.getAcquisitionToolVersion(),
                evidence.getAcquisitionNotes());
    }

    /**
     * Overlays only the present request fields on the complete {@code before} snapshot and validates the resulting
     * complete state. Absent fields keep the locked aggregate value, explicit nulls clear optional fields, blank
     * optional text is trimmed to null, and every length limit is checked after normalization.
     */
    private static EvidenceMetadataSnapshot merge(
            EvidenceMetadataSnapshot before, PatchEvidenceMetadataRequest request, Instant createdAt) {
        Instant acquiredAt = request.has(Field.ACQUIRED_AT) ? request.getAcquiredAt() : before.acquiredAt();
        if (acquiredAt != null) {
            acquiredAt = acquiredAt.truncatedTo(ChronoUnit.MICROS);
            if (acquiredAt.isAfter(createdAt)) {
                throw new EvidenceRequestValidationException();
            }
        }
        try {
            return new EvidenceMetadataSnapshot(
                    request.has(Field.TITLE) ? request.getTitle() : before.title(),
                    request.has(Field.DESCRIPTION) ? request.getDescription() : before.description(),
                    request.has(Field.SOURCE_TYPE) ? request.getSourceType() : before.sourceType(),
                    request.has(Field.SOURCE_DESCRIPTION) ? request.getSourceDescription() : before.sourceDescription(),
                    request.has(Field.SOURCE_MANUFACTURER)
                            ? request.getSourceManufacturer()
                            : before.sourceManufacturer(),
                    request.has(Field.SOURCE_MODEL) ? request.getSourceModel() : before.sourceModel(),
                    request.has(Field.SOURCE_SERIAL_NUMBER)
                            ? request.getSourceSerialNumber()
                            : before.sourceSerialNumber(),
                    request.has(Field.SOURCE_LOGICAL_IDENTIFIER)
                            ? request.getSourceLogicalIdentifier()
                            : before.sourceLogicalIdentifier(),
                    request.has(Field.ACQUISITION_METHOD) ? request.getAcquisitionMethod() : before.acquisitionMethod(),
                    acquiredAt,
                    request.has(Field.ACQUISITION_LOCATION)
                            ? request.getAcquisitionLocation()
                            : before.acquisitionLocation(),
                    request.has(Field.ACQUISITION_TOOL_NAME)
                            ? request.getAcquisitionToolName()
                            : before.acquisitionToolName(),
                    request.has(Field.ACQUISITION_TOOL_VERSION)
                            ? request.getAcquisitionToolVersion()
                            : before.acquisitionToolVersion(),
                    request.has(Field.ACQUISITION_NOTES) ? request.getAcquisitionNotes() : before.acquisitionNotes());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new EvidenceRequestValidationException();
        }
    }

    /** Applies the validated complete target state through the aggregate, which re-validates every invariant. */
    private static void applyTo(DigitalEvidence evidence, EvidenceMetadataSnapshot target) {
        try {
            evidence.updateMetadata(target.title(), target.description());
            evidence.updateSourceMetadata(
                    target.sourceType(),
                    target.sourceDescription(),
                    target.sourceManufacturer(),
                    target.sourceModel(),
                    target.sourceSerialNumber(),
                    target.sourceLogicalIdentifier());
            evidence.updateAcquisitionMetadata(
                    target.acquisitionMethod(),
                    target.acquisitionLocation(),
                    target.acquisitionToolName(),
                    target.acquisitionToolVersion(),
                    target.acquisitionNotes(),
                    target.acquiredAt());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new EvidenceRequestValidationException();
        } catch (IllegalStateException exception) {
            throw new InvalidEvidenceStateException("Only evidence in custody can change descriptive metadata.");
        }
    }
}
