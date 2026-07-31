package it.itsprodigi.proofchain.evidence.application;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.EvidenceReleasedPayload;
import it.itsprodigi.proofchain.evidence.api.EvidenceOperationResponse;
import it.itsprodigi.proofchain.evidence.api.ReleaseEvidenceRequest;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.domain.EvidenceStatus;
import it.itsprodigi.proofchain.operator.domain.Operator;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Evidence release workflow built on the shared Sprint 5 operational command foundation.
 *
 * <p>The service owns only the workflow-specific part: the {@code IN_CUSTODY} or {@code SEALED} source-state gate, the
 * capture of the previous status and previous holder, the terminal transition delegated to the domain aggregate and the
 * frozen Sprint 4 {@code EVIDENCE_RELEASED} payload. Visibility, method security, the authorization matrix, the frozen
 * lock order, the single command instant, conflict translation and response mapping are all reused from {@link
 * EvidenceOperationalCommandService}.
 *
 * <p>Release is the one operational command a member {@code EVIDENCE_OFFICER} may never issue: only {@code ADMIN}
 * globally and a member {@code CASE_MANAGER} can terminate custody. Unlike sealing, it deliberately does not require
 * the previous holder to still be eligible, so management can end custody even when a recovery transfer was never
 * performed; the captured previous holder is still recorded in the payload.
 *
 * <p>The holder is cleared before the event is constructed, while the captured previous holder identifier is already
 * held locally, so the committed aggregate and the appended event can never disagree.
 *
 * <p>Logging is operational and sanitized: the reason text, the payload, holder profile details, hashes, storage data
 * and the request body are never logged.
 */
@Service
public class EvidenceReleaseService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EvidenceReleaseService.class);
    private static final String COMMAND = "RELEASE";
    private static final Set<EvidenceStatus> RELEASABLE_STATUSES =
            EnumSet.of(EvidenceStatus.IN_CUSTODY, EvidenceStatus.SEALED);

    private final EvidenceOperationalCommandService commands;

    public EvidenceReleaseService(EvidenceOperationalCommandService commands) {
        this.commands = Objects.requireNonNull(commands, "commands must not be null");
    }

    @PreAuthorize("isAuthenticated()")
    public EvidenceOperationResponse release(
            UUID evidenceId, ReleaseEvidenceRequest request, AuthenticatedOperator actor) {
        Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        if (request == null) {
            throw new EvidenceRequestValidationException();
        }
        String reason = EvidenceCommandReason.require(request.reason());
        AtomicReference<EvidenceStatus> priorStatus = new AtomicReference<>();

        EvidenceOperationResponse response;
        try {
            response = commands.execute(
                    EvidenceOperationalCommand.EVIDENCE_RELEASE,
                    evidenceId,
                    actor,
                    context -> apply(context, reason, priorStatus));
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Evidence release result=failure command={} evidenceId={} actorId={} newStatus={} failureCategory={}",
                    COMMAND,
                    evidenceId,
                    actor.id(),
                    EvidenceStatus.RELEASED,
                    exception.getClass().getSimpleName());
            throw exception;
        }
        LOGGER.info(
                "Evidence release result=success failureCategory=none command={} caseId={} evidenceId={} actorId={} priorStatus={} newStatus={} eventId={} sequenceNumber={}",
                COMMAND,
                response.evidence().caseId(),
                response.evidence().id(),
                actor.id(),
                priorStatus.get(),
                response.evidence().status(),
                response.eventSummary().id(),
                response.eventSummary().sequenceNumber());
        return response;
    }

    /**
     * Workflow body executed with the custody case read lock and the evidence write lock already held, after shared
     * authorization, case status and lifecycle checks and after the single command instant has been generated.
     */
    private static CustodyEventPayload apply(
            EvidenceCommandContext context, String reason, AtomicReference<EvidenceStatus> priorStatus) {
        DigitalEvidence evidence = context.evidence();
        EvidenceStatus previousStatus = evidence.getStatus();
        if (!RELEASABLE_STATUSES.contains(previousStatus)) {
            throw new InvalidEvidenceStateException("Only IN_CUSTODY or SEALED evidence can be released.");
        }
        Operator previousHolder = evidence.getCurrentHolder();
        if (previousHolder == null) {
            throw new InvalidEvidenceStateException("Evidence without a current holder cannot be released.");
        }
        UUID previousHolderId = previousHolder.getId();
        priorStatus.set(previousStatus);
        evidence.release();
        return new EvidenceReleasedPayload(previousStatus, EvidenceStatus.RELEASED, previousHolderId, null, reason);
    }
}
