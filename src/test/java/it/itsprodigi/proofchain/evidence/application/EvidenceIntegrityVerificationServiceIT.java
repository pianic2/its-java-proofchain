package it.itsprodigi.proofchain.evidence.application;

import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.custodyCase;
import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.operator;
import static it.itsprodigi.proofchain.support.OperationalCommandTestSupport.authenticated;
import static it.itsprodigi.proofchain.support.OperationalCommandTestSupport.principal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import it.itsprodigi.proofchain.common.exception.ResourceNotFoundException;
import it.itsprodigi.proofchain.custodycase.application.CaseClosedException;
import it.itsprodigi.proofchain.custodycase.domain.CaseMembership;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.custodycase.persistence.CustodyCaseRepository;
import it.itsprodigi.proofchain.custodyevent.application.CustodyEventConcurrencyConflictException;
import it.itsprodigi.proofchain.custodyevent.application.CustodyEventPersistenceFailureException;
import it.itsprodigi.proofchain.custodyevent.domain.CustodyEvent;
import it.itsprodigi.proofchain.custodyevent.domain.EventType;
import it.itsprodigi.proofchain.custodyevent.persistence.CustodyEventRepository;
import it.itsprodigi.proofchain.custodyevent.protocol.CanonicalCustodyEvent;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventCanonicalizer;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventHashing;
import it.itsprodigi.proofchain.custodyevent.protocol.IntegrityVerifiedPayload;
import it.itsprodigi.proofchain.evidence.api.IntegrityVerificationResponse;
import it.itsprodigi.proofchain.evidence.domain.AcquisitionMethod;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.domain.EvidenceStatus;
import it.itsprodigi.proofchain.evidence.domain.SourceType;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceRepository;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL and real-filesystem behavior of the file-integrity verification workflow.
 *
 * <p>The decisive distinction is proven here: a readable file always completes with a deterministic valid or invalid
 * result and exactly one appended event, while a technical inability to read the exact bytes appends nothing and leaves
 * the aggregate, the chain and the file untouched.
 */
class EvidenceIntegrityVerificationServiceIT extends PostgreSqlIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final byte[] CONTENT = "proofchain-integrity-🔐".getBytes(StandardCharsets.UTF_8);

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void configureStorage(DynamicPropertyRegistry registry) {
        registry.add("proofchain.storage.root", () -> storageRoot.toString());
        registry.add("proofchain.storage.max-file-size", () -> "1MB");
    }

    private final AtomicInteger referenceTags = new AtomicInteger();

    @Autowired
    private EvidenceIntegrityVerificationService verification;

    @MockitoSpyBean
    private EvidenceStoragePort storage;

    @MockitoSpyBean
    private CustodyEventRepository events;

    @MockitoSpyBean
    private DigitalEvidenceRepository evidences;

    @Autowired
    private CaseMembershipRepository memberships;

    @Autowired
    private CustodyCaseRepository custodyCases;

    @Autowired
    private OperatorRepository operators;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Operator admin;
    private Operator manager;
    private Operator officer;
    private Operator otherOfficer;
    private Operator auditor;
    private Operator outsider;
    private CustodyCase owningCase;

    @BeforeEach
    void setUp() throws IOException {
        cleanDatabaseInDependencyOrder();
        cleanStorage();
        admin = operators.saveAndFlush(operator("integrity-admin", OperatorRole.ADMIN));
        manager = operators.saveAndFlush(operator("integrity-manager", OperatorRole.CASE_MANAGER));
        officer = operators.saveAndFlush(operator("integrity-officer", OperatorRole.EVIDENCE_OFFICER));
        otherOfficer = operators.saveAndFlush(operator("integrity-other-officer", OperatorRole.EVIDENCE_OFFICER));
        auditor = operators.saveAndFlush(operator("integrity-auditor", OperatorRole.AUDITOR));
        outsider = operators.saveAndFlush(operator("integrity-outsider", OperatorRole.CASE_MANAGER));
        owningCase = custodyCases.saveAndFlush(custodyCase("Integrity case", manager));
        assign(manager, officer, otherOfficer, auditor);
    }

    @AfterEach
    void tearDown() throws IOException {
        reset(storage, events, evidences);
        SecurityContextHolder.clearContext();
        cleanDatabaseInDependencyOrder();
        cleanStorage();
    }

    /**
     * The comparison matrix: {@code valid} is the conjunction of digest equality and observed-size equality, and every
     * one of the four rows is a completed verification that answers with the appended event.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Comparison.class)
    void comparesBothTheDigestAndTheObservedByteCountAndAlwaysCompletes(Comparison comparison) {
        String actualSha256 = sha256(CONTENT);
        long actualSize = CONTENT.length;
        DigitalEvidence target = storedEvidence(
                CONTENT,
                comparison.hashMatches ? actualSha256 : "f".repeat(64),
                comparison.sizeMatches ? actualSize : actualSize + 1);

        IntegrityVerificationResponse response = verifyIntegrity(auditor, target.getId());

        assertThat(response.valid()).isEqualTo(comparison.valid);
        assertThat(response.evidenceId()).isEqualTo(target.getId());
        assertThat(response.actualContentSha256()).isEqualTo(actualSha256).matches("[0-9a-f]{64}");
        assertThat(response.actualFileSize()).isEqualTo(actualSize);
        assertThat(response.expectedContentSha256()).isEqualTo(comparison.hashMatches ? actualSha256 : "f".repeat(64));
        assertThat(response.expectedFileSize()).isEqualTo(comparison.sizeMatches ? actualSize : actualSize + 1);

        List<CustodyEvent> timeline = timeline(target.getId());
        assertThat(timeline).hasSize(1);
        CustodyEvent appended = timeline.getFirst();
        assertThat(appended.getEventType()).isEqualTo(EventType.INTEGRITY_VERIFIED);
        assertThat(response.eventSummary().id()).isEqualTo(appended.getId());
        assertThat(response.eventSummary().sequenceNumber()).isEqualTo(1L);

        JsonNode payload = JSON.readTree(appended.getPayloadJson());
        assertThat(payload.propertyNames())
                .containsExactlyInAnyOrder(
                        "algorithm", "expectedContentSha256", "actualContentSha256", "valid", "fileSize");
        assertThat(payload.get("algorithm").stringValue()).isEqualTo("SHA-256");
        assertThat(payload.get("valid").booleanValue()).isEqualTo(comparison.valid);
        assertThat(payload.get("actualContentSha256").stringValue()).isEqualTo(actualSha256);
        assertThat(payload.get("fileSize").longValue())
                .as("the payload fileSize is the actually observed byte count")
                .isEqualTo(actualSize);
        assertThat(JSON.readTree(appended.getPayloadJson()))
                .isEqualTo(JSON.readTree(CustodyEventCanonicalizer.canonicalizePayload(new IntegrityVerifiedPayload(
                        IntegrityVerifiedPayload.SHA_256,
                        response.expectedContentSha256(),
                        actualSha256,
                        comparison.valid,
                        actualSize))));
        assertThat(appended.getEventHash())
                .isEqualTo(CustodyEventHashing.eventHash(canonical(
                        appended,
                        target,
                        auditor,
                        new IntegrityVerifiedPayload(
                                IntegrityVerifiedPayload.SHA_256,
                                response.expectedContentSha256(),
                                actualSha256,
                                comparison.valid,
                                actualSize))));
    }

    /** Exact binary vectors prove the digest is computed over the exact bytes across buffer boundaries. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Vector.class)
    void reproducesExactBinarySha256VectorsIncludingMultiBufferContent(Vector vector) {
        byte[] bytes = vector.bytes();
        DigitalEvidence target = storedEvidence(bytes, sha256(bytes), bytes.length);

        IntegrityVerificationResponse response = verifyIntegrity(manager, target.getId());

        assertThat(response.actualContentSha256()).isEqualTo(vector.expectedSha256());
        assertThat(response.actualFileSize()).isEqualTo(bytes.length);
        assertThat(response.valid()).isTrue();
    }

    /** Every evidence status is verifiable while the case is open, including the terminal {@code RELEASED}. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(EvidenceStatus.class)
    void verifiesEveryEvidenceStatusIncludingTheTerminalReleasedOne(EvidenceStatus status) {
        DigitalEvidence target = storedEvidence(CONTENT, sha256(CONTENT), CONTENT.length);
        transitionTo(target, status);

        IntegrityVerificationResponse response = verifyIntegrity(officer, target.getId());

        assertThat(response.valid()).isTrue();
        assertThat(reload(target).getStatus())
                .as("verification never changes the lifecycle")
                .isEqualTo(status);
        assertThat(events.countByEvidenceId(target.getId())).isEqualTo(1L);
    }

    @Test
    void refusesAClosedCaseBeforeAnyStorageAccess() {
        DigitalEvidence target = storedEvidence(CONTENT, sha256(CONTENT), CONTENT.length);
        DigitalEvidence stored = reload(target);
        CustodyCase managedCase = custodyCases.findById(owningCase.getId()).orElseThrow();
        managedCase.close();
        custodyCases.saveAndFlush(managedCase);
        reset(storage);

        assertThatThrownBy(() -> verifyIntegrity(manager, target.getId())).isInstanceOf(CaseClosedException.class);

        verify(storage, never()).open(anyString());
        DigitalEvidence unchanged = reload(target);
        assertThat(events.countByEvidenceId(target.getId())).isZero();
        assertThat(unchanged.getCustodyEventCount()).isZero();
        assertThat(unchanged.getCustodyChainHeadHash()).isEqualTo(CustodyEventHashing.ZERO_HASH);
        assertThat(unchanged.getUpdatedAt()).isEqualTo(stored.getUpdatedAt());
        assertThat(unchanged.getVersion()).isEqualTo(stored.getVersion());
    }

    /** Every visible member may verify, auditors included; only a non-member is hidden. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Caller.class)
    void allowsAdminAndEveryCaseMemberIncludingAuditors(Caller caller) {
        DigitalEvidence target = storedEvidence(CONTENT, sha256(CONTENT), CONTENT.length);
        Operator actor = callerOperator(caller);
        ThrowingCallable command = () -> verifyIntegrity(actor, target.getId());

        if (caller.allowed) {
            assertThatCode(command).doesNotThrowAnyException();
            assertThat(events.countByEvidenceId(target.getId())).isEqualTo(1L);
        } else {
            assertThatThrownBy(command).isInstanceOf(ResourceNotFoundException.class);
            assertThat(events.countByEvidenceId(target.getId())).isZero();
        }
    }

    @Test
    void hidesExistingEvidenceExactlyLikeMissingEvidence() {
        DigitalEvidence target = storedEvidence(CONTENT, sha256(CONTENT), CONTENT.length);

        ResourceNotFoundException hidden = notFound(() -> verifyIntegrity(outsider, target.getId()));
        ResourceNotFoundException missing = notFound(() -> verifyIntegrity(outsider, UUID.randomUUID()));

        assertThat(hidden).hasSameClassAs(missing).hasMessage(missing.getMessage());
        assertThat(events.countByEvidenceId(target.getId())).isZero();
    }

    /** One microsecond instant reaches the aggregate, the persisted event and the response. */
    @Test
    void sharesOneMicrosecondInstantBetweenTheAggregateTheEventAndTheResponse() {
        DigitalEvidence target = storedEvidence(CONTENT, sha256(CONTENT), CONTENT.length);
        Instant before = reload(target).getUpdatedAt();

        IntegrityVerificationResponse response = verifyIntegrity(manager, target.getId());

        CustodyEvent appended = timeline(target.getId()).getFirst();
        Instant verifiedAt = response.verifiedAt();
        assertThat(verifiedAt)
                .isEqualTo(verifiedAt.truncatedTo(ChronoUnit.MICROS))
                .isEqualTo(response.eventSummary().occurredAt())
                .isEqualTo(appended.getOccurredAt())
                .isEqualTo(reload(target).getUpdatedAt());
        assertThat(verifiedAt).isAfterOrEqualTo(before);
    }

    /** A non-conforming observation is a finding, never a mutation of the stored evidence or of the stored bytes. */
    @Test
    void aNonConformingResultNeverRewritesStoredMetadataOrStoredBytes() throws IOException {
        DigitalEvidence target = storedEvidence(CONTENT, "a".repeat(64), CONTENT.length + 7);
        DigitalEvidence stored = reload(target);
        Path content = storageRoot.resolve(stored.getStorageKey());
        byte[] bytesBefore = Files.readAllBytes(content);

        IntegrityVerificationResponse response = verifyIntegrity(manager, target.getId());

        DigitalEvidence after = reload(target);
        assertThat(response.valid()).isFalse();
        assertThat(after.getContentSha256())
                .isEqualTo(stored.getContentSha256())
                .isEqualTo("a".repeat(64));
        assertThat(after.getFileSize()).isEqualTo(stored.getFileSize()).isEqualTo(CONTENT.length + 7L);
        assertThat(after.getContextualSha256()).isEqualTo(stored.getContextualSha256());
        assertThat(after.getStatus()).isEqualTo(stored.getStatus());
        assertThat(Files.readAllBytes(content)).isEqualTo(bytesBefore).isEqualTo(CONTENT);
        assertThat(after.getCustodyEventCount()).isEqualTo(1L);
        assertThat(timeline(target.getId())).hasSize(1);
    }

    /** Repeated verification is intentionally non-idempotent: every run appends its own gapless linked event. */
    @Test
    void repeatedVerificationAppendsSeparateGaplessEvents() {
        DigitalEvidence target = storedEvidence(CONTENT, sha256(CONTENT), CONTENT.length);

        IntegrityVerificationResponse first = verifyIntegrity(manager, target.getId());
        IntegrityVerificationResponse second = verifyIntegrity(auditor, target.getId());

        List<CustodyEvent> timeline = timeline(target.getId());
        DigitalEvidence after = reload(target);
        assertThat(List.of(
                        first.eventSummary().sequenceNumber(),
                        second.eventSummary().sequenceNumber()))
                .containsExactly(1L, 2L);
        assertThat(first.eventSummary().id()).isNotEqualTo(second.eventSummary().id());
        assertThat(timeline)
                .extracting(CustodyEvent::getEventType)
                .containsExactly(EventType.INTEGRITY_VERIFIED, EventType.INTEGRITY_VERIFIED);
        assertThat(timeline.getFirst().getPreviousHash()).isEqualTo(CustodyEventHashing.ZERO_HASH);
        assertThat(timeline.getLast().getPreviousHash())
                .isEqualTo(timeline.getFirst().getEventHash());
        assertThat(after.getCustodyEventCount()).isEqualTo(2L);
        assertThat(after.getCustodyChainHeadHash()).isEqualTo(timeline.getLast().getEventHash());
        assertThat(first.valid()).isTrue();
        assertThat(second.valid()).isTrue();
    }

    /**
     * Technical inability never produces a verification event: the transaction is aborted before the append, so the
     * aggregate, the chain and the file are all exactly as they were.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Unavailable.class)
    void technicalInabilityAppendsNoEventAndChangesNothing(Unavailable unavailable) throws IOException {
        DigitalEvidence target = storedEvidence(CONTENT, sha256(CONTENT), CONTENT.length);
        DigitalEvidence stored = reload(target);
        Path content = storageRoot.resolve(stored.getStorageKey());
        unavailable.prepare(this, content);

        assertThatThrownBy(() -> verifyIntegrity(manager, target.getId())).isInstanceOf(unavailable.expected);

        DigitalEvidence unchanged = reload(target);
        assertThat(events.countByEvidenceId(target.getId())).isZero();
        assertThat(unchanged.getCustodyEventCount()).isZero();
        assertThat(unchanged.getCustodyChainHeadHash()).isEqualTo(CustodyEventHashing.ZERO_HASH);
        assertThat(unchanged.getUpdatedAt()).isEqualTo(stored.getUpdatedAt());
        assertThat(unchanged.getVersion()).isEqualTo(stored.getVersion());
        assertThat(unchanged.getContentSha256()).isEqualTo(stored.getContentSha256());
        assertThat(unchanged.getFileSize()).isEqualTo(stored.getFileSize());

        reset(storage);
        if (unavailable.restorable) {
            deleteRecursively(content);
            Files.createDirectories(content.getParent());
            Files.write(content, CONTENT);
            assertThat(verifyIntegrity(manager, target.getId()).eventSummary().sequenceNumber())
                    .isEqualTo(1L);
        }
    }

    /** Any failure between the observation and the commit rolls back the event and leaves the file untouched. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(WriteFailure.class)
    void rollsBackTheAppendAndLeavesTheFileAndTheChainUnchanged(WriteFailure failure) throws IOException {
        DigitalEvidence target = storedEvidence(CONTENT, sha256(CONTENT), CONTENT.length);
        DigitalEvidence stored = reload(target);
        Path content = storageRoot.resolve(stored.getStorageKey());
        byte[] bytesBefore = Files.readAllBytes(content);
        failure.arm(this);

        assertThatThrownBy(() -> verifyIntegrity(manager, target.getId())).isInstanceOf(failure.expected);

        DigitalEvidence unchanged = reload(target);
        assertThat(events.countByEvidenceId(target.getId())).isZero();
        assertThat(unchanged.getCustodyEventCount()).isZero();
        assertThat(unchanged.getCustodyChainHeadHash()).isEqualTo(CustodyEventHashing.ZERO_HASH);
        assertThat(unchanged.getUpdatedAt()).isEqualTo(stored.getUpdatedAt());
        assertThat(Files.readAllBytes(content)).isEqualTo(bytesBefore);

        reset(events, evidences);
        assertThat(verifyIntegrity(manager, target.getId()).eventSummary().sequenceNumber())
                .isEqualTo(1L);
    }

    private enum Comparison {
        HASH_EQUAL_SIZE_EQUAL(true, true, true),
        HASH_DIFFERENT_SIZE_EQUAL(false, true, false),
        HASH_EQUAL_SIZE_DIFFERENT(true, false, false),
        BOTH_DIFFERENT(false, false, false);

        private final boolean hashMatches;
        private final boolean sizeMatches;
        private final boolean valid;

        Comparison(boolean hashMatches, boolean sizeMatches, boolean valid) {
            this.hashMatches = hashMatches;
            this.sizeMatches = sizeMatches;
            this.valid = valid;
        }
    }

    private enum Vector {
        SINGLE_ZERO_BYTE("6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d"),
        ABC("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
        EXACTLY_ONE_BUFFER("9f1dcbc35c350d6027f98be0f5c8b43b42ca52b7604459c0c42be3aa88913d47"),
        ACROSS_MANY_BUFFERS("cc17faaad36649c4603dda4d8ff97cb149722af0bcac0746305a2134ad2d0b97");

        private final String expectedSha256;

        Vector(String expectedSha256) {
            this.expectedSha256 = expectedSha256;
        }

        private byte[] bytes() {
            return switch (this) {
                case SINGLE_ZERO_BYTE -> new byte[1];
                case ABC -> "abc".getBytes(StandardCharsets.UTF_8);
                case EXACTLY_ONE_BUFFER -> new byte[8192];
                case ACROSS_MANY_BUFFERS -> repeated((byte) 'a', 20_000);
            };
        }

        private String expectedSha256() {
            return expectedSha256;
        }

        private static byte[] repeated(byte value, int length) {
            byte[] bytes = new byte[length];
            Arrays.fill(bytes, value);
            return bytes;
        }
    }

    private enum Caller {
        GLOBAL_ADMIN(true),
        MEMBER_CASE_MANAGER(true),
        MEMBER_EVIDENCE_OFFICER_HOLDER(true),
        MEMBER_EVIDENCE_OFFICER_NON_HOLDER(true),
        MEMBER_AUDITOR(true),
        NON_MEMBER_CASE_MANAGER(false);

        private final boolean allowed;

        Caller(boolean allowed) {
            this.allowed = allowed;
        }
    }

    /** Missing, non-regular, symlinked, empty, unreadable and adapter failures, with their sanitized mapping. */
    private enum Unavailable {
        MISSING_FILE(EvidenceFileUnavailableException.class, true),
        DIRECTORY_INSTEAD_OF_FILE(EvidenceFileUnavailableException.class, true),
        SYMLINKED_FILE(EvidenceFileUnavailableException.class, true),
        EMPTY_STORED_FILE(EvidenceFileUnavailableException.class, true),
        UNREADABLE_FILE(EvidenceFileUnavailableException.class, false),
        UNSAFE_STORAGE_PATH(EvidenceFileUnavailableException.class, false),
        FAILING_CONTENT_STREAM(EvidenceFileUnavailableException.class, false),
        ADAPTER_FAILURE(EvidenceStorageFailureException.class, false);

        private final Class<? extends RuntimeException> expected;
        private final boolean restorable;

        Unavailable(Class<? extends RuntimeException> expected, boolean restorable) {
            this.expected = expected;
            this.restorable = restorable;
        }

        private void prepare(EvidenceIntegrityVerificationServiceIT test, Path content) throws IOException {
            switch (this) {
                case MISSING_FILE -> Files.delete(content);
                case DIRECTORY_INSTEAD_OF_FILE -> {
                    Files.delete(content);
                    Files.createDirectory(content);
                }
                case SYMLINKED_FILE -> {
                    Path elsewhere = content.resolveSibling("elsewhere.bin");
                    Files.write(elsewhere, CONTENT);
                    Files.delete(content);
                    Files.createSymbolicLink(content, elsewhere);
                }
                case EMPTY_STORED_FILE -> Files.write(content, new byte[0]);
                case UNREADABLE_FILE ->
                    doThrow(new EvidenceFileUnavailableException())
                            .when(test.storage)
                            .open(anyString());
                case UNSAFE_STORAGE_PATH ->
                    doThrow(new UnsafeEvidenceStoragePathException())
                            .when(test.storage)
                            .open(anyString());
                case FAILING_CONTENT_STREAM ->
                    doAnswer(invocation -> new OpenedEvidence(
                                    invocation.getArgument(0, String.class), CONTENT.length, new FailingInputStream()))
                            .when(test.storage)
                            .open(anyString());
                case ADAPTER_FAILURE ->
                    doThrow(new EvidenceStorageFailureException("forced adapter failure"))
                            .when(test.storage)
                            .open(anyString());
            }
        }
    }

    private enum WriteFailure {
        APPENDER_INSERT(CustodyEventPersistenceFailureException.class),
        AGGREGATE_FLUSH(CustodyEventPersistenceFailureException.class),
        OPTIMISTIC_CONFLICT(CustodyEventConcurrencyConflictException.class);

        private final Class<? extends RuntimeException> expected;

        WriteFailure(Class<? extends RuntimeException> expected) {
            this.expected = expected;
        }

        private void arm(EvidenceIntegrityVerificationServiceIT test) {
            switch (this) {
                case APPENDER_INSERT ->
                    doThrow(new DataIntegrityViolationException("forced custody event insert failure"))
                            .when(test.events)
                            .saveAndFlush(any(CustodyEvent.class));
                case AGGREGATE_FLUSH ->
                    doThrow(new DataIntegrityViolationException("forced aggregate flush failure"))
                            .when(test.evidences)
                            .saveAndFlush(any(DigitalEvidence.class));
                case OPTIMISTIC_CONFLICT ->
                    doThrow(new ObjectOptimisticLockingFailureException(DigitalEvidence.class, UUID.randomUUID()))
                            .when(test.evidences)
                            .saveAndFlush(any(DigitalEvidence.class));
            }
        }
    }

    /** Content that opens cleanly but fails while being streamed. */
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

    private Operator callerOperator(Caller caller) {
        return switch (caller) {
            case GLOBAL_ADMIN -> admin;
            case MEMBER_CASE_MANAGER -> manager;
            case MEMBER_EVIDENCE_OFFICER_HOLDER -> officer;
            case MEMBER_EVIDENCE_OFFICER_NON_HOLDER -> otherOfficer;
            case MEMBER_AUDITOR -> auditor;
            case NON_MEMBER_CASE_MANAGER -> outsider;
        };
    }

    private IntegrityVerificationResponse verifyIntegrity(Operator actor, UUID evidenceId) {
        return authenticated(actor, () -> verification.verify(evidenceId, principal(actor)));
    }

    private DigitalEvidence storedEvidence(byte[] bytes, String recordedSha256, long recordedFileSize) {
        UUID evidenceId = UUID.randomUUID();
        String storageKey = EvidenceStorageKeyFactory.forEvidence(owningCase.getId(), evidenceId);
        DigitalEvidence evidence = evidences.saveAndFlush(DigitalEvidence.create(
                evidenceId,
                owningCase,
                officer,
                manager,
                "INT" + referenceTags.incrementAndGet(),
                "Forensic disk image",
                null,
                SourceType.DEVICE,
                null,
                null,
                null,
                null,
                null,
                AcquisitionMethod.PHYSICAL,
                null,
                null,
                null,
                null,
                Instant.EPOCH,
                "disk-image.E01",
                "application/octet-stream",
                recordedFileSize,
                recordedSha256,
                EvidenceHashing.contextualSha256(owningCase.getId(), evidenceId, recordedSha256),
                storageKey));
        StagedEvidence staged = storage.stage(storageKey, new ByteArrayInputStream(bytes));
        storage.finalizeStaged(staged);
        return evidence;
    }

    private void transitionTo(DigitalEvidence evidence, EvidenceStatus status) {
        if (status == EvidenceStatus.IN_CUSTODY) {
            return;
        }
        DigitalEvidence managed = evidences.findById(evidence.getId()).orElseThrow();
        if (status == EvidenceStatus.SEALED) {
            managed.seal();
        } else {
            managed.release();
        }
        evidences.saveAndFlush(managed);
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(EvidenceHashing.newContentDigest().digest(bytes));
    }

    private List<CustodyEvent> timeline(UUID evidenceId) {
        return events.findAllByEvidenceIdOrderBySequenceNumberAsc(evidenceId);
    }

    private DigitalEvidence reload(DigitalEvidence evidence) {
        return evidences.findByIdForVisibility(evidence.getId()).orElseThrow();
    }

    private CanonicalCustodyEvent canonical(
            CustodyEvent event, DigitalEvidence evidence, Operator actor, IntegrityVerifiedPayload payload) {
        return new CanonicalCustodyEvent(
                event.getId(),
                owningCase.getId(),
                evidence.getId(),
                actor.getId(),
                actor.getRole(),
                event.getSequenceNumber(),
                event.getEventType(),
                event.getOccurredAt(),
                event.getPayloadVersion(),
                payload,
                event.getPreviousHash());
    }

    private void assign(Operator... members) {
        for (Operator member : members) {
            memberships.save(CaseMembership.assign(owningCase, member, manager));
        }
        memberships.flush();
    }

    private static ResourceNotFoundException notFound(ThrowingCallable action) {
        try {
            action.call();
        } catch (ResourceNotFoundException exception) {
            return exception;
        } catch (Throwable failure) {
            throw new AssertionError("Expected a ResourceNotFoundException", failure);
        }
        throw new AssertionError("Expected a ResourceNotFoundException");
    }

    private void cleanDatabaseInDependencyOrder() {
        jdbcTemplate.execute("TRUNCATE TABLE custody_events");
        evidences.deleteAllInBatch();
        memberships.deleteAllInBatch();
        custodyCases.deleteAllInBatch();
        operators.deleteAllInBatch();
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path candidate : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(candidate);
            }
        }
    }

    private static void cleanStorage() throws IOException {
        if (!Files.exists(storageRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(storageRoot)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(storageRoot)) {
                    Files.deleteIfExists(path);
                }
            }
        }
        Files.createDirectories(storageRoot.resolve(".staging"));
    }
}
