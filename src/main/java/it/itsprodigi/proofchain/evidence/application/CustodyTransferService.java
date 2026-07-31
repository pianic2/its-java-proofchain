package it.itsprodigi.proofchain.evidence.application;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyTransferredPayload;
import it.itsprodigi.proofchain.evidence.api.EvidenceOperationResponse;
import it.itsprodigi.proofchain.evidence.api.TransferCustodyRequest;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.domain.EvidenceStatus;
import it.itsprodigi.proofchain.operator.domain.Operator;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Custody transfer workflow built on the shared Sprint 5 operational command foundation.
 *
 * <p>The service owns only the workflow-specific part: request normalization, target holder eligibility, the no-op
 * guard, the holder mutation and the frozen {@code CUSTODY_TRANSFERRED} payload. Visibility, method security,
 * authorization, the frozen lock order, the single command instant, conflict translation and response mapping are
 * reused from {@link EvidenceOperationalCommandService}.
 *
 * <p>A transfer is never a status transition: {@code IN_CUSTODY} stays {@code IN_CUSTODY} and {@code SEALED} stays
 * {@code SEALED}. Only the target holder is eligibility-checked, so an {@code ADMIN} or a member {@code CASE_MANAGER}
 * can recover evidence from a suspended, disabled or otherwise ineligible current holder.
 *
 * <p>Logging is operational and sanitized: the reason text, the payload, holder profile details, hashes, storage data
 * and the request body are never logged.
 */
@Service
public class CustodyTransferService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CustodyTransferService.class);
    private static final String COMMAND = "TRANSFER";
    private static final Set<EvidenceStatus> TRANSFERABLE_STATUSES =
            EnumSet.of(EvidenceStatus.IN_CUSTODY, EvidenceStatus.SEALED);

    private final EvidenceOperationalCommandService commands;
    private final CaseMembershipRepository memberships;

    public CustodyTransferService(EvidenceOperationalCommandService commands, CaseMembershipRepository memberships) {
        this.commands = Objects.requireNonNull(commands, "commands must not be null");
        this.memberships = Objects.requireNonNull(memberships, "memberships must not be null");
    }

    @PreAuthorize("isAuthenticated()")
    public EvidenceOperationResponse transfer(
            UUID evidenceId, TransferCustodyRequest request, AuthenticatedOperator actor) {
        Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        if (request == null || request.newHolderId() == null) {
            throw new EvidenceRequestValidationException();
        }
        UUID newHolderId = request.newHolderId();
        String reason = EvidenceCommandReason.require(request.reason());

        EvidenceOperationResponse response;
        try {
            response = commands.execute(
                    EvidenceOperationalCommand.CUSTODY_TRANSFER,
                    evidenceId,
                    actor,
                    context -> apply(context, newHolderId, reason));
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Custody transfer result=failure command={} evidenceId={} actorId={} targetHolderId={} failureCategory={}",
                    COMMAND,
                    evidenceId,
                    actor.id(),
                    newHolderId,
                    exception.getClass().getSimpleName());
            throw exception;
        }
        LOGGER.info(
                "Custody transfer result=success failureCategory=none command={} caseId={} evidenceId={} actorId={} targetHolderId={} eventId={} sequenceNumber={}",
                COMMAND,
                response.evidence().caseId(),
                response.evidence().id(),
                actor.id(),
                newHolderId,
                response.eventSummary().id(),
                response.eventSummary().sequenceNumber());
        return response;
    }

    /**
     * Workflow body executed with the custody case read lock and the evidence write lock already held, after shared
     * authorization, case status and lifecycle checks and after the single command instant has been generated.
     */
    private CustodyEventPayload apply(EvidenceCommandContext context, UUID newHolderId, String reason) {
        DigitalEvidence evidence = context.evidence();
        if (!TRANSFERABLE_STATUSES.contains(evidence.getStatus())) {
            throw new InvalidEvidenceStateException("Only IN_CUSTODY or SEALED evidence can change holder.");
        }
        Operator target = memberships
                .findEligibleEvidenceHolder(context.custodyCase().getId(), newHolderId)
                .orElseThrow(EvidenceHolderNotEligibleException::new);
        Operator previousHolder = evidence.getCurrentHolder();
        if (previousHolder == null) {
            throw new InvalidEvidenceStateException("Evidence without a current holder cannot change holder.");
        }
        UUID previousHolderId = previousHolder.getId();
        if (previousHolderId.equals(target.getId())) {
            throw new CustodyTransferNoOpException();
        }
        evidence.transferTo(target);
        return new CustodyTransferredPayload(previousHolderId, target.getId(), reason);
    }
}
