package it.itsprodigi.proofchain.evidence.application;

import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.IntegrityVerifiedPayload;
import it.itsprodigi.proofchain.evidence.api.EvidenceOperationResponse;
import it.itsprodigi.proofchain.evidence.api.IntegrityVerificationResponse;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * File-integrity verification workflow built on the shared Sprint 5 operational command foundation.
 *
 * <p>The service owns only the workflow-specific part: opening the exact stored file through the hardened storage
 * port, one bounded-memory streaming pass that produces both the SHA-256 digest and the actually observed byte count,
 * the comparison against the locked persisted metadata and the frozen Sprint 4 {@code INTEGRITY_VERIFIED} payload.
 * Visibility, method security, authorization, the frozen lock order, the single command instant, conflict translation
 * and the custody event append are all reused from {@link EvidenceOperationalCommandService}.
 *
 * <p>The read runs inside the shared transaction body, so the custody case read lock and the evidence write lock are
 * still held while the file is streamed: transfer, seal, release and metadata update cannot interleave between the
 * observed aggregate metadata and the appended verification event. Nothing is written to the filesystem and no
 * persisted hash, size or contextual hash is ever rewritten.
 *
 * <p>A completed verification is always a success, whether the content conforms or not. Only a technical inability to
 * read the exact bytes is an error, and it aborts the transaction before any event is appended.
 *
 * <p>Logging is operational and sanitized: file bytes, storage keys, absolute paths, payload JSON, canonical preimages
 * and full hashes are never logged.
 */
@Service
public class EvidenceIntegrityVerificationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EvidenceIntegrityVerificationService.class);
    private static final String COMMAND = "VERIFY_INTEGRITY";
    private static final int BUFFER_SIZE = 8192;

    private final EvidenceOperationalCommandService commands;
    private final EvidenceStoragePort storage;

    public EvidenceIntegrityVerificationService(
            EvidenceOperationalCommandService commands, EvidenceStoragePort storage) {
        this.commands = Objects.requireNonNull(commands, "commands must not be null");
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
    }

    @PreAuthorize("isAuthenticated()")
    public IntegrityVerificationResponse verify(UUID evidenceId, AuthenticatedOperator actor) {
        Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        AtomicReference<ObservedIntegrity> observed = new AtomicReference<>();

        EvidenceOperationResponse operation;
        try {
            operation = commands.execute(
                    EvidenceOperationalCommand.INTEGRITY_VERIFICATION,
                    evidenceId,
                    actor,
                    context -> apply(context, observed));
        } catch (RuntimeException exception) {
            logFailure(evidenceId, actor, exception);
            throw exception;
        }

        ObservedIntegrity result = Objects.requireNonNull(observed.get(), "the verification result must be observed");
        IntegrityVerificationResponse response = new IntegrityVerificationResponse(
                operation.evidence().id(),
                result.valid(),
                result.expectedContentSha256(),
                result.actualContentSha256(),
                result.expectedFileSize(),
                result.actualFileSize(),
                result.verifiedAt(),
                operation.eventSummary());
        logCompleted(operation, actor, response);
        return response;
    }

    /**
     * Workflow body executed with the custody case read lock and the evidence write lock already held, after shared
     * authorization and case status checks and after the single command instant has been generated.
     *
     * <p>Every value compared here comes from the locked aggregate and from the completed storage read; nothing is
     * taken from the request, which carries no body at all.
     */
    private CustodyEventPayload apply(EvidenceCommandContext context, AtomicReference<ObservedIntegrity> sink) {
        DigitalEvidence evidence = context.evidence();
        String expectedContentSha256 = evidence.getContentSha256();
        long expectedFileSize = evidence.getFileSize();

        ObservedContent content = readStoredContent(evidence.getStorageKey());
        boolean valid =
                expectedContentSha256.equals(content.contentSha256()) && expectedFileSize == content.byteCount();

        evidence.stampCommandInstant(context.occurredAt());
        sink.set(new ObservedIntegrity(
                expectedContentSha256,
                content.contentSha256(),
                expectedFileSize,
                content.byteCount(),
                valid,
                context.occurredAt()));
        return new IntegrityVerifiedPayload(
                IntegrityVerifiedPayload.SHA_256,
                expectedContentSha256,
                content.contentSha256(),
                valid,
                content.byteCount());
    }

    /**
     * Streams the exact stored bytes once through the hardened storage port, updating the digest and the overflow-safe
     * byte counter from a fixed buffer. The content is never materialized in memory, never read through an absolute
     * path, never resolved through a symbolic link and never modified.
     */
    private ObservedContent readStoredContent(String storageKey) {
        try (OpenedEvidence opened = openStoredContent(storageKey)) {
            MessageDigest digest = EvidenceHashing.newContentDigest();
            InputStream content = opened.content();
            byte[] buffer = new byte[BUFFER_SIZE];
            long byteCount = 0L;
            while (true) {
                int read = content.read(buffer, 0, BUFFER_SIZE);
                if (read == -1) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                digest.update(buffer, 0, read);
                byteCount = Math.addExact(byteCount, read);
            }
            if (byteCount == 0L) {
                // Registered evidence can never be empty, so empty stored content is unusable, not an observation.
                throw new EvidenceFileUnavailableException();
            }
            return new ObservedContent(EvidenceHashing.hex(digest.digest()), byteCount);
        } catch (IOException exception) {
            throw new EvidenceFileUnavailableException();
        } catch (ArithmeticException exception) {
            throw new EvidenceStorageFailureException("Evidence content exceeds the addressable byte count");
        }
    }

    /** Missing, non-regular, unreadable and unsafe content are all indistinguishable technical inabilities. */
    private OpenedEvidence openStoredContent(String storageKey) {
        try {
            return storage.open(storageKey);
        } catch (UnsafeEvidenceStoragePathException exception) {
            throw new EvidenceFileUnavailableException();
        }
    }

    private static void logCompleted(
            EvidenceOperationResponse operation, AuthenticatedOperator actor, IntegrityVerificationResponse response) {
        String message =
                "Evidence integrity verification result=completed failureCategory=none command={} caseId={} evidenceId={} actorId={} valid={} eventId={} sequenceNumber={}";
        Object[] arguments = {
            COMMAND,
            operation.evidence().caseId(),
            operation.evidence().id(),
            actor.id(),
            response.valid(),
            operation.eventSummary().id(),
            operation.eventSummary().sequenceNumber()
        };
        if (response.valid()) {
            LOGGER.info(message, arguments);
        } else {
            LOGGER.warn(message, arguments);
        }
    }

    private static void logFailure(UUID evidenceId, AuthenticatedOperator actor, RuntimeException exception) {
        String message =
                "Evidence integrity verification result=failure command={} evidenceId={} actorId={} failureCategory={}";
        Object[] arguments = {
            COMMAND, evidenceId, actor.id(), exception.getClass().getSimpleName()
        };
        if (exception instanceof EvidenceStorageException) {
            LOGGER.error(message, arguments);
        } else {
            LOGGER.warn(message, arguments);
        }
    }

    /** Digest and byte count produced by exactly one streaming pass over the stored file. */
    private record ObservedContent(String contentSha256, long byteCount) {}

    /** Complete comparison observed while both locks were held, carried out to the specialized response. */
    private record ObservedIntegrity(
            String expectedContentSha256,
            String actualContentSha256,
            long expectedFileSize,
            long actualFileSize,
            boolean valid,
            Instant verifiedAt) {}
}
