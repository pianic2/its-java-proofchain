package it.itsprodigi.proofchain.evidence.application;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.EvidenceSealedPayload;
import it.itsprodigi.proofchain.evidence.api.EvidenceOperationResponse;
import it.itsprodigi.proofchain.evidence.api.SealEvidenceRequest;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.domain.EvidenceStatus;
import it.itsprodigi.proofchain.operator.domain.Operator;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Evidence seal workflow built on the shared Sprint 5 operational command foundation.
 *
 * <p>The service owns only the workflow-specific part: the {@code IN_CUSTODY} source-state gate, the current-holder
 * eligibility proof, the {@code IN_CUSTODY -> SEALED} transition delegated to the domain aggregate and the frozen
 * Sprint 4 {@code EVIDENCE_SEALED} payload. Visibility, method security, the authorization matrix, the frozen lock
 * order, the single command instant, conflict translation and response mapping are all reused from {@link
 * EvidenceOperationalCommandService}.
 *
 * <p>Sealing never changes the holder. It does require the current holder to still be an {@code ACTIVE} member of the
 * owning case with a custody-capable role, because a seal freezes the custody chain around whoever holds the evidence:
 * an ineligible holder must be replaced by an explicit recovery transfer first. The holder is never automatically
 * changed or cleared here, and every ineligibility cause maps to the same conflict.
 *
 * <p>Logging is operational and sanitized: the reason text, the payload, holder profile details, hashes, storage data
 * and the request body are never logged.
 */
@Service
public class EvidenceSealService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EvidenceSealService.class);
    private static final String COMMAND = "SEAL";

    private final EvidenceOperationalCommandService commands;
    private final CaseMembershipRepository memberships;

    public EvidenceSealService(EvidenceOperationalCommandService commands, CaseMembershipRepository memberships) {
        this.commands = Objects.requireNonNull(commands, "commands must not be null");
        this.memberships = Objects.requireNonNull(memberships, "memberships must not be null");
    }

    @PreAuthorize("isAuthenticated()")
    public EvidenceOperationResponse seal(UUID evidenceId, SealEvidenceRequest request, AuthenticatedOperator actor) {
        Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        if (request == null) {
            throw new EvidenceRequestValidationException();
        }
        String reason = EvidenceCommandReason.require(request.reason());

        EvidenceOperationResponse response;
        try {
            response = commands.execute(
                    EvidenceOperationalCommand.EVIDENCE_SEAL, evidenceId, actor, context -> apply(context, reason));
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Evidence seal result=failure command={} evidenceId={} actorId={} newStatus={} failureCategory={}",
                    COMMAND,
                    evidenceId,
                    actor.id(),
                    EvidenceStatus.SEALED,
                    exception.getClass().getSimpleName());
            throw exception;
        }
        LOGGER.info(
                "Evidence seal result=success failureCategory=none command={} caseId={} evidenceId={} actorId={} priorStatus={} newStatus={} eventId={} sequenceNumber={}",
                COMMAND,
                response.evidence().caseId(),
                response.evidence().id(),
                actor.id(),
                EvidenceStatus.IN_CUSTODY,
                response.evidence().status(),
                response.eventSummary().id(),
                response.eventSummary().sequenceNumber());
        return response;
    }

    /**
     * Workflow body executed with the custody case read lock and the evidence write lock already held, after shared
     * authorization, case status and lifecycle checks and after the single command instant has been generated.
     *
     * <p>The source state, the holder and the payload all come from the locked aggregate, so the eligibility proof and
     * the appended event describe exactly the state that is committed.
     */
    private CustodyEventPayload apply(EvidenceCommandContext context, String reason) {
        DigitalEvidence evidence = context.evidence();
        EvidenceStatus previousStatus = evidence.getStatus();
        if (previousStatus != EvidenceStatus.IN_CUSTODY) {
            throw new InvalidEvidenceStateException("Only evidence in custody can be sealed.");
        }
        Operator holder = evidence.getCurrentHolder();
        if (holder == null) {
            throw new InvalidEvidenceStateException("Evidence without a current holder cannot be sealed.");
        }
        Operator eligibleHolder = memberships
                .findEligibleEvidenceHolder(context.custodyCase().getId(), holder.getId())
                .orElseThrow(EvidenceHolderNotEligibleException::new);
        evidence.seal();
        return new EvidenceSealedPayload(previousStatus, EvidenceStatus.SEALED, eligibleHolder.getId(), reason);
    }
}
