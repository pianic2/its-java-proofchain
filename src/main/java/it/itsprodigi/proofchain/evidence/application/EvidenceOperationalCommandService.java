package it.itsprodigi.proofchain.evidence.application;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.evidence.api.EvidenceOperationResponse;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Entry point every Sprint 5 operational workflow reuses.
 *
 * <p>Enforces method security, runs the shared transaction template and translates technical write conflicts without
 * any retry. Logging is operational and sanitized: reasons, request bodies, payload JSON, storage keys and content
 * hashes are never logged.
 */
@Service
public class EvidenceOperationalCommandService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EvidenceOperationalCommandService.class);

    private final EvidenceOperationalCommandTransaction transaction;
    private final EvidenceCommandConflictTranslator conflicts;

    public EvidenceOperationalCommandService(
            EvidenceOperationalCommandTransaction transaction, EvidenceCommandConflictTranslator conflicts) {
        this.transaction = Objects.requireNonNull(transaction, "transaction must not be null");
        this.conflicts = Objects.requireNonNull(conflicts, "conflicts must not be null");
    }

    @PreAuthorize("isAuthenticated()")
    public EvidenceOperationResponse execute(
            EvidenceOperationalCommand command,
            UUID evidenceId,
            AuthenticatedOperator actor,
            EvidenceCommandBody body) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(body, "body must not be null");
        EvidenceOperationResponse response;
        try {
            response = conflicts.translating(() -> transaction.execute(command, evidenceId, actor, body));
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Operational custody command result=failure command={} evidenceId={} actorId={} failureCategory={}",
                    command.commandName(),
                    evidenceId,
                    actor.id(),
                    exception.getClass().getSimpleName());
            throw exception;
        }
        LOGGER.info(
                "Operational custody command result=success failureCategory=none command={} caseId={} evidenceId={} actorId={} eventId={} sequenceNumber={}",
                command.commandName(),
                response.evidence().caseId(),
                response.evidence().id(),
                actor.id(),
                response.eventSummary().id(),
                response.eventSummary().sequenceNumber());
        return response;
    }
}
