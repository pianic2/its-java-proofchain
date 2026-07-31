package it.itsprodigi.proofchain.evidence.application;

import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.custodyCase;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.evidence;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.operator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodyevent.api.CustodyEventSummaryResponse;
import it.itsprodigi.proofchain.custodyevent.application.CustodyEventAppendResult;
import it.itsprodigi.proofchain.custodyevent.domain.EventType;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventHashing;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.IntegrityVerifiedPayload;
import it.itsprodigi.proofchain.evidence.api.EvidenceOperationResponse;
import it.itsprodigi.proofchain.evidence.api.EvidenceResponse;
import it.itsprodigi.proofchain.evidence.api.IntegrityVerificationResponse;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit contract of the verification workflow body: one bounded-memory streaming pass, the exact payload built from the
 * locked aggregate, and sanitized operational logging at the documented level for each outcome.
 */
class EvidenceIntegrityVerificationServiceTest {

    private static final byte[] CONTENT = "unit-integrity-content".getBytes(StandardCharsets.UTF_8);
    private static final String CONTENT_SHA_256 =
            HexFormat.of().formatHex(EvidenceHashing.newContentDigest().digest(CONTENT));

    private final CapturingAppender appender = new CapturingAppender();
    private final AtomicReference<CustodyEventPayload> appendedPayload = new AtomicReference<>();

    private Logger logger;
    private Operator actor;
    private CustodyCase owningCase;
    private DigitalEvidence target;
    private Instant occurredAt;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(EvidenceIntegrityVerificationService.class);
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        actor = operator("unit-auditor", OperatorRole.AUDITOR);
        owningCase = custodyCase("Unit integrity case", actor);
        target = evidence(owningCase, actor, "UNITINT");
        occurredAt = target.getCreatedAt().plus(1, ChronoUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
    }

    /** The exact bytes are streamed once through a fixed buffer; nothing is slurped and no size shortcut is used. */
    @Test
    void streamsTheStoredContentOnceInBoundedMemoryAndClosesIt() {
        RecordingInputStream content = new RecordingInputStream(CONTENT);
        EvidenceIntegrityVerificationService service = service(port(content, CONTENT.length), matchingMetadata());

        IntegrityVerificationResponse response = service.verify(target.getId(), principal());

        assertThat(response.valid()).isTrue();
        assertThat(response.actualContentSha256()).isEqualTo(CONTENT_SHA_256);
        assertThat(response.actualFileSize()).isEqualTo(CONTENT.length);
        assertThat(content.largestRequestedLength).isEqualTo(8192);
        assertThat(content.singleByteReads).isZero();
        assertThat(content.availableCalls).isZero();
        assertThat(content.closed).isTrue();
        assertThat(appendedPayload.get())
                .isEqualTo(new IntegrityVerifiedPayload(
                        IntegrityVerifiedPayload.SHA_256, CONTENT_SHA_256, CONTENT_SHA_256, true, CONTENT.length));
        assertThat(target.getUpdatedAt())
                .as("the single command instant also stamps the aggregate")
                .isEqualTo(occurredAt)
                .isEqualTo(response.verifiedAt());
        assertThat(logged(Level.INFO)).hasSize(1);
        assertThat(logged(Level.WARN)).isEmpty();
        assertThat(logged(Level.ERROR)).isEmpty();
        assertThatLogsAreSanitized();
    }

    /** A size-only contradiction is invalid even though the digests are identical, and it logs at WARN. */
    @Test
    void reportsASizeOnlyContradictionAsInvalidAndLogsAtWarn() {
        EvidenceIntegrityVerificationService service =
                service(port(new RecordingInputStream(CONTENT), CONTENT.length), sizeMismatchMetadata());

        IntegrityVerificationResponse response = service.verify(target.getId(), principal());

        assertThat(response.valid()).isFalse();
        assertThat(response.expectedContentSha256()).isEqualTo(response.actualContentSha256());
        assertThat(response.expectedFileSize()).isNotEqualTo(response.actualFileSize());
        assertThat(appendedPayload.get())
                .isEqualTo(new IntegrityVerifiedPayload(
                        IntegrityVerifiedPayload.SHA_256, CONTENT_SHA_256, CONTENT_SHA_256, false, CONTENT.length));
        assertThat(logged(Level.WARN)).hasSize(1);
        assertThat(logged(Level.INFO)).isEmpty();
        assertThat(logged(Level.ERROR)).isEmpty();
        assertThatLogsAreSanitized();
    }

    /** Unsafe storage paths are indistinguishable from unavailable content, and technical inability logs at ERROR. */
    @Test
    void mapsUnsafeStoragePathsToUnavailableContentAndLogsTechnicalInabilityAtError() {
        EvidenceStoragePort storage = mock(EvidenceStoragePort.class);
        when(storage.open(anyString())).thenThrow(new UnsafeEvidenceStoragePathException());
        EvidenceIntegrityVerificationService service = service(storage, matchingMetadata());

        assertThatThrownBy(() -> service.verify(target.getId(), principal()))
                .isInstanceOf(EvidenceFileUnavailableException.class);

        assertThat(appendedPayload.get()).isNull();
        assertThat(logged(Level.ERROR)).hasSize(1);
        assertThat(logged(Level.INFO)).isEmpty();
        assertThatLogsAreSanitized();
    }

    /** Empty stored content cannot be a registered evidence file, so it is a technical inability, not a result. */
    @Test
    void treatsEmptyStoredContentAsUnavailableInsteadOfAnObservation() {
        EvidenceIntegrityVerificationService service =
                service(port(new RecordingInputStream(new byte[0]), 0), matchingMetadata());

        assertThatThrownBy(() -> service.verify(target.getId(), principal()))
                .isInstanceOf(EvidenceFileUnavailableException.class);

        assertThat(appendedPayload.get()).isNull();
        assertThat(logged(Level.ERROR)).hasSize(1);
    }

    /** A stream that fails midway is unreadable content, never a partial observation. */
    @Test
    void neverProducesAResultFromAPartiallyReadableStream() {
        EvidenceIntegrityVerificationService service = service(port(new FailingInputStream(), CONTENT.length), null);

        assertThatThrownBy(() -> service.verify(target.getId(), principal()))
                .isInstanceOf(EvidenceFileUnavailableException.class);

        assertThat(appendedPayload.get()).isNull();
        assertThat(logged(Level.ERROR)).hasSize(1);
    }

    private void assertThatLogsAreSanitized() {
        List<String> messages =
                appender.events.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).isNotEmpty();
        for (String message : messages) {
            assertThat(message)
                    .contains("command=VERIFY_INTEGRITY")
                    .doesNotContain(CONTENT_SHA_256)
                    .doesNotContain(target.getStorageKey())
                    .doesNotContain(target.getContentSha256())
                    .doesNotContain("content.bin")
                    .doesNotContain(new String(CONTENT, StandardCharsets.UTF_8));
        }
    }

    private List<ILoggingEvent> logged(Level level) {
        return appender.events.stream()
                .filter(event -> event.getLevel() == level)
                .toList();
    }

    private EvidenceStoragePort port(InputStream content, long byteCount) {
        EvidenceStoragePort storage = mock(EvidenceStoragePort.class);
        when(storage.open(anyString()))
                .thenAnswer(
                        invocation -> new OpenedEvidence(invocation.getArgument(0, String.class), byteCount, content));
        return storage;
    }

    /** Persisted metadata that exactly describes the stored content. */
    private DigitalEvidence matchingMetadata() {
        return rebuild(CONTENT_SHA_256, CONTENT.length);
    }

    /** Persisted metadata whose digest matches but whose recorded size contradicts the observed byte count. */
    private DigitalEvidence sizeMismatchMetadata() {
        return rebuild(CONTENT_SHA_256, CONTENT.length + 11L);
    }

    private DigitalEvidence rebuild(String contentSha256, long fileSize) {
        UUID evidenceId = target.getId();
        target = DigitalEvidence.create(
                evidenceId,
                owningCase,
                actor,
                actor,
                target.getReferenceTag(),
                target.getTitle(),
                null,
                target.getSourceType(),
                null,
                null,
                null,
                null,
                null,
                target.getAcquisitionMethod(),
                null,
                null,
                null,
                null,
                Instant.EPOCH,
                target.getOriginalFilename(),
                target.getMediaType(),
                fileSize,
                contentSha256,
                EvidenceHashing.contextualSha256(owningCase.getId(), evidenceId, contentSha256),
                target.getStorageKey());
        occurredAt = target.getCreatedAt().plus(1, ChronoUnit.SECONDS);
        return target;
    }

    /**
     * Runs the workflow body exactly the way the shared operational transaction does: both locks held, authorization
     * and case status already re-checked, and the single command instant already generated.
     */
    private EvidenceIntegrityVerificationService service(EvidenceStoragePort storage, DigitalEvidence locked) {
        DigitalEvidence evidence = locked == null ? target : locked;
        EvidenceOperationalCommandService commands = mock(EvidenceOperationalCommandService.class);
        when(commands.execute(eq(EvidenceOperationalCommand.INTEGRITY_VERIFICATION), any(UUID.class), any(), any()))
                .thenAnswer(invocation -> {
                    EvidenceCommandBody body = invocation.getArgument(3);
                    CustodyEventPayload payload = body.apply(new EvidenceCommandContext(
                            EvidenceOperationalCommand.INTEGRITY_VERIFICATION,
                            owningCase,
                            evidence,
                            actor,
                            occurredAt));
                    appendedPayload.set(payload);
                    return operationResponse(evidence);
                });
        return new EvidenceIntegrityVerificationService(commands, storage);
    }

    private EvidenceOperationResponse operationResponse(DigitalEvidence evidence) {
        EvidenceResponse evidenceResponse = mock(EvidenceResponse.class);
        when(evidenceResponse.id()).thenReturn(evidence.getId());
        when(evidenceResponse.caseId()).thenReturn(owningCase.getId());
        CustodyEventAppendResult appended = new CustodyEventAppendResult(
                UUID.randomUUID(),
                1L,
                EventType.INTEGRITY_VERIFIED,
                occurredAt,
                CustodyEventHashing.ZERO_HASH,
                "a".repeat(64),
                1,
                1);
        return new EvidenceOperationResponse(
                evidenceResponse,
                new CustodyEventSummaryResponse(
                        appended.eventId(),
                        owningCase.getId(),
                        evidence.getId(),
                        appended.sequenceNumber(),
                        appended.eventType(),
                        actor.getId(),
                        actor.getRole(),
                        appended.occurredAt(),
                        appended.hashVersion(),
                        appended.payloadVersion(),
                        appended.previousHash(),
                        appended.eventHash()));
    }

    private AuthenticatedOperator principal() {
        return new AuthenticatedOperator(
                actor.getId(),
                actor.getUsername(),
                actor.getEmail(),
                actor.getFirstName(),
                actor.getLastName(),
                actor.getRole(),
                actor.getStatus(),
                actor.getCreatedAt(),
                actor.getUpdatedAt());
    }

    /** Records how the content was consumed so bounded-memory streaming can be asserted. */
    private static final class RecordingInputStream extends ByteArrayInputStream {

        private int largestRequestedLength;
        private int singleByteReads;
        private int availableCalls;
        private boolean closed;

        private RecordingInputStream(byte[] content) {
            super(content);
        }

        @Override
        public synchronized int read() {
            singleByteReads++;
            return super.read();
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) {
            largestRequestedLength = Math.max(largestRequestedLength, length);
            return super.read(buffer, offset, length);
        }

        @Override
        public synchronized int available() {
            availableCalls++;
            return super.available();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class FailingInputStream extends InputStream {

        @Override
        public int read() throws IOException {
            throw new IOException("forced read failure");
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            throw new IOException("forced read failure");
        }
    }

    private static final class CapturingAppender extends AppenderBase<ILoggingEvent> {

        private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            events.add(event);
        }
    }
}
