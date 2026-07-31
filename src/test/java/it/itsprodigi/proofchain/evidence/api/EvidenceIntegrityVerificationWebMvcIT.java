package it.itsprodigi.proofchain.evidence.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.itsprodigi.proofchain.auth.application.JwtTokenService;
import it.itsprodigi.proofchain.custodycase.domain.CaseMembership;
import it.itsprodigi.proofchain.custodycase.domain.CasePriority;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.custodycase.persistence.CustodyCaseRepository;
import it.itsprodigi.proofchain.custodyevent.persistence.CustodyEventRepository;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventHashing;
import it.itsprodigi.proofchain.evidence.application.EvidenceHashing;
import it.itsprodigi.proofchain.evidence.application.EvidenceStorageKeyFactory;
import it.itsprodigi.proofchain.evidence.application.EvidenceStoragePort;
import it.itsprodigi.proofchain.evidence.application.StagedEvidence;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP contract of the canonical file-integrity verification endpoint.
 *
 * <p>Both a conforming and a non-conforming verification are successful completions with the canonical event location,
 * every case member including {@code AUDITOR} may run it, and the specialized response never leaks storage keys, chain
 * anchors or the optimistic version.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EvidenceIntegrityVerificationWebMvcIT extends PostgreSqlIntegrationTest {

    private static final String VERIFY_PATH = "/api/v1/evidences/{evidenceId}/verify-integrity";
    private static final byte[] CONTENT = "http-integrity-🔐".getBytes(StandardCharsets.UTF_8);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void configureStorage(DynamicPropertyRegistry registry) {
        registry.add("proofchain.storage.root", () -> storageRoot.toString());
        registry.add("proofchain.storage.max-file-size", () -> "1MB");
    }

    private final AtomicInteger referenceTags = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService tokens;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private OperatorRepository operators;

    @Autowired
    private CustodyCaseRepository custodyCases;

    @Autowired
    private CaseMembershipRepository memberships;

    @Autowired
    private DigitalEvidenceRepository evidences;

    @Autowired
    private CustodyEventRepository events;

    @Autowired
    private EvidenceStoragePort storage;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Operator admin;
    private Operator manager;
    private Operator officer;
    private Operator auditor;
    private Operator outsider;
    private CustodyCase owningCase;

    @BeforeEach
    void setUp() throws IOException {
        cleanDatabase();
        cleanStorage();
        admin = saveOperator("mvc-integrity-admin", OperatorRole.ADMIN);
        manager = saveOperator("mvc-integrity-manager", OperatorRole.CASE_MANAGER);
        officer = saveOperator("mvc-integrity-officer", OperatorRole.EVIDENCE_OFFICER);
        auditor = saveOperator("mvc-integrity-auditor", OperatorRole.AUDITOR);
        outsider = saveOperator("mvc-integrity-outsider", OperatorRole.CASE_MANAGER);
        owningCase = custodyCases.saveAndFlush(
                CustodyCase.create("Integrity HTTP case", null, null, null, null, CasePriority.HIGH, manager));
        assign(manager, officer, auditor);
    }

    @AfterEach
    void tearDown() throws IOException {
        cleanDatabase();
        cleanStorage();
    }

    /** A conforming verification answers 200 with the canonical event location and the specialized sanitized body. */
    @Test
    void returnsTheCanonicalEventLocationAndTheExactSpecializedBody() throws Exception {
        DigitalEvidence target = storedEvidence(CONTENT, sha256(CONTENT), CONTENT.length);

        MvcResult result = verify(target.getId(), auditor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(8)))
                .andExpect(jsonPath("$.evidenceId").value(target.getId().toString()))
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.expectedContentSha256").value(sha256(CONTENT)))
                .andExpect(jsonPath("$.actualContentSha256").value(sha256(CONTENT)))
                .andExpect(jsonPath("$.expectedFileSize").value(CONTENT.length))
                .andExpect(jsonPath("$.actualFileSize").value(CONTENT.length))
                .andExpect(jsonPath("$.verifiedAt").exists())
                .andExpect(jsonPath("$.eventSummary.eventType").value("INTEGRITY_VERIFIED"))
                .andExpect(jsonPath("$.eventSummary.sequenceNumber").value(1))
                .andExpect(jsonPath("$.eventSummary.payload").doesNotExist())
                .andExpect(jsonPath("$.eventSummary.payloadJson").doesNotExist())
                .andExpect(jsonPath("$.evidence").doesNotExist())
                .andExpect(jsonPath("$.storageKey").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist())
                .andExpect(jsonPath("$.custodyEventCount").doesNotExist())
                .andExpect(jsonPath("$.custodyChainHeadHash").doesNotExist())
                .andReturn();

        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo("/api/v1/evidences/%s/events/%s"
                        .formatted(
                                target.getId(),
                                body.get("eventSummary").get("id").stringValue()));
        assertThat(body.get("verifiedAt").stringValue())
                .isEqualTo(body.get("eventSummary").get("occurredAt").stringValue());
        assertThat(events.countByEvidenceId(target.getId())).isEqualTo(1L);
    }

    /** A non-conforming verification is a finding, not an error: still 200, still exactly one appended event. */
    @Test
    void reportsANonConformingResultAsASuccessfulCompletionWithoutMutatingAnything() throws Exception {
        DigitalEvidence target = storedEvidence(CONTENT, "d".repeat(64), CONTENT.length + 3);
        Path content = storageRoot.resolve(reload(target).getStorageKey());
        byte[] before = Files.readAllBytes(content);

        MvcResult result = verify(target.getId(), manager)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.expectedContentSha256").value("d".repeat(64)))
                .andExpect(jsonPath("$.actualContentSha256").value(sha256(CONTENT)))
                .andExpect(jsonPath("$.expectedFileSize").value(CONTENT.length + 3))
                .andExpect(jsonPath("$.actualFileSize").value(CONTENT.length))
                .andExpect(jsonPath("$.type").doesNotExist())
                .andReturn();

        assertThat(result.getResponse().getHeader("Location")).isNotNull();
        DigitalEvidence after = reload(target);
        assertThat(after.getContentSha256()).isEqualTo("d".repeat(64));
        assertThat(after.getFileSize()).isEqualTo(CONTENT.length + 3L);
        assertThat(Files.readAllBytes(content)).isEqualTo(before);
        assertThat(events.countByEvidenceId(target.getId())).isEqualTo(1L);
    }

    /** Every visible reader may verify; hidden and nonexistent evidence stay indistinguishable. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Caller.class)
    void allowsAdminAndEveryMemberIncludingAuditorsAndHidesEverythingElse(Caller caller) throws Exception {
        DigitalEvidence target = storedEvidence(CONTENT, sha256(CONTENT), CONTENT.length);
        Operator actor = callerOperator(caller);

        if (caller.allowed) {
            verify(target.getId(), actor)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.valid").value(true));
            assertThat(events.countByEvidenceId(target.getId())).isEqualTo(1L);
            return;
        }

        String hidden = verify(target.getId(), actor)
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String missing = verify(UUID.randomUUID(), actor)
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(withoutVolatileFields(hidden)).isEqualTo(withoutVolatileFields(missing));
        assertThat(events.countByEvidenceId(target.getId())).isZero();
    }

    @Test
    void requiresAuthenticationAndRejectsAClosedCaseWithoutAppendingAnything() throws Exception {
        DigitalEvidence target = storedEvidence(CONTENT, sha256(CONTENT), CONTENT.length);

        mockMvc.perform(post(VERIFY_PATH, target.getId())).andExpect(status().isUnauthorized());

        CustodyCase managedCase = custodyCases.findById(owningCase.getId()).orElseThrow();
        managedCase.close();
        custodyCases.saveAndFlush(managedCase);

        verify(target.getId(), manager)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/case-closed"));
        assertThat(events.countByEvidenceId(target.getId())).isZero();
        assertThat(reload(target).getCustodyChainHeadHash()).isEqualTo(CustodyEventHashing.ZERO_HASH);
    }

    /** Every evidence status stays verifiable while the case is open, including the terminal RELEASED one. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(EvidenceStatus.class)
    void verifiesEveryEvidenceStatus(EvidenceStatus status) throws Exception {
        DigitalEvidence target = storedEvidence(CONTENT, sha256(CONTENT), CONTENT.length);
        transitionTo(target, status);

        verify(target.getId(), officer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        assertThat(reload(target).getStatus()).isEqualTo(status);
        assertThat(events.countByEvidenceId(target.getId())).isEqualTo(1L);
    }

    @Test
    void missingStoredContentIsASanitizedTechnicalErrorWithoutAnyEvent() throws Exception {
        DigitalEvidence target = storedEvidence(CONTENT, sha256(CONTENT), CONTENT.length);
        Files.delete(storageRoot.resolve(reload(target).getStorageKey()));

        MvcResult result = verify(target.getId(), manager)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/evidence-file-unavailable"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(storageRoot.toString())
                .doesNotContain("content.bin")
                .doesNotContain("IOException")
                .doesNotContain("NoSuchFile");
        assertThat(events.countByEvidenceId(target.getId())).isZero();
        assertThat(reload(target).getCustodyEventCount()).isZero();
    }

    /**
     * The endpoint takes no command from the caller: a forged body carrying a hash, a size or a verdict is ignored and
     * the result is still computed from the stored bytes and the persisted metadata only.
     */
    @Test
    void ignoresAnyRequestBodyAndNeverTrustsClientSuppliedHashSizeOrVerdict() throws Exception {
        DigitalEvidence target = storedEvidence(CONTENT, sha256(CONTENT), CONTENT.length);
        String forged = "{\"valid\":false,\"contentSha256\":\"%s\",\"fileSize\":1,\"storageKey\":\"../../etc/passwd\"}"
                .formatted("e".repeat(64));

        mockMvc.perform(post(VERIFY_PATH, target.getId())
                        .header("Authorization", bearer(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forged))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.expectedContentSha256").value(sha256(CONTENT)))
                .andExpect(jsonPath("$.actualFileSize").value(CONTENT.length));

        assertThat(events.countByEvidenceId(target.getId())).isEqualTo(1L);
    }

    @Test
    void documentsExactlyOneCanonicalVerificationOperationWithNoRequestBodyAndNoAlias() throws Exception {
        MvcResult result =
                mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        JsonNode document = JSON.readTree(result.getResponse().getContentAsString());
        JsonNode paths = document.get("paths");
        JsonNode operation = paths.get(VERIFY_PATH).get("post");

        assertThat(paths.propertyNames())
                .filteredOn(path -> path.contains("verify-integrity"))
                .containsExactly(VERIFY_PATH);
        assertThat(paths.get(VERIFY_PATH).propertyNames()).containsExactly("post");
        assertThat(operation.get("operationId").stringValue()).isEqualTo("verifyEvidenceIntegrity");
        assertThat(operation.get("requestBody")).isNull();
        assertThat(operation.get("security").get(0).has("bearerAuth")).isTrue();
        assertThat(operation.get("responses").propertyNames())
                .containsExactlyInAnyOrder("200", "400", "401", "404", "409", "500");
        assertThat(operation
                        .get("responses")
                        .get("200")
                        .get("content")
                        .get("application/json")
                        .get("schema")
                        .get("$ref")
                        .stringValue())
                .isEqualTo("#/components/schemas/IntegrityVerificationResponse");
        assertThat(operation.get("responses").get("200").get("headers").has("Location"))
                .isTrue();
        assertThat(document.get("components")
                        .get("schemas")
                        .get("IntegrityVerificationResponse")
                        .get("properties")
                        .propertyNames())
                .containsExactlyInAnyOrder(
                        "evidenceId",
                        "valid",
                        "expectedContentSha256",
                        "actualContentSha256",
                        "expectedFileSize",
                        "actualFileSize",
                        "verifiedAt",
                        "eventSummary");

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['" + VERIFY_PATH + "'].get").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/evidences/verify-integrity']")
                        .doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/cases/{caseId}/evidences/verify-integrity']")
                        .doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/evidences/{evidenceId}/integrity']")
                        .doesNotExist());
    }

    @Test
    void rejectsEveryOtherMethodAndTheBatchAndAliasShapesAtRuntime() throws Exception {
        DigitalEvidence target = storedEvidence(CONTENT, sha256(CONTENT), CONTENT.length);

        mockMvc.perform(get(VERIFY_PATH, target.getId()).header("Authorization", bearer(manager)))
                .andExpect(notCompleted("no GET alias may ever run a verification"));
        mockMvc.perform(post("/api/v1/evidences/verify-integrity").header("Authorization", bearer(manager)))
                .andExpect(notCompleted("no batch verification endpoint exists"));
        mockMvc.perform(post("/api/v1/cases/{caseId}/evidences/verify-integrity", owningCase.getId())
                        .header("Authorization", bearer(manager)))
                .andExpect(notCompleted("no case-nested verification alias exists"));
        assertThat(events.countByEvidenceId(target.getId())).isZero();
    }

    private enum Caller {
        GLOBAL_ADMIN(true),
        MEMBER_CASE_MANAGER(true),
        MEMBER_EVIDENCE_OFFICER(true),
        MEMBER_AUDITOR(true),
        NON_MEMBER_CASE_MANAGER(false);

        private final boolean allowed;

        Caller(boolean allowed) {
            this.allowed = allowed;
        }
    }

    private Operator callerOperator(Caller caller) {
        return switch (caller) {
            case GLOBAL_ADMIN -> admin;
            case MEMBER_CASE_MANAGER -> manager;
            case MEMBER_EVIDENCE_OFFICER -> officer;
            case MEMBER_AUDITOR -> auditor;
            case NON_MEMBER_CASE_MANAGER -> outsider;
        };
    }

    /** No shape other than the canonical one may ever answer with a completed verification. */
    private static ResultMatcher notCompleted(String description) {
        return result ->
                assertThat(result.getResponse().getStatus()).as(description).isNotIn(200, 201, 202, 204);
    }

    private ResultActions verify(UUID evidenceId, Operator actor) throws Exception {
        return mockMvc.perform(post(VERIFY_PATH, evidenceId).header("Authorization", bearer(actor)));
    }

    private DigitalEvidence storedEvidence(byte[] bytes, String recordedSha256, long recordedFileSize) {
        UUID evidenceId = UUID.randomUUID();
        String storageKey = EvidenceStorageKeyFactory.forEvidence(owningCase.getId(), evidenceId);
        DigitalEvidence evidence = evidences.saveAndFlush(DigitalEvidence.create(
                evidenceId,
                owningCase,
                officer,
                manager,
                "HTTPINT" + referenceTags.incrementAndGet(),
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
                MediaType.APPLICATION_OCTET_STREAM_VALUE,
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

    private DigitalEvidence reload(DigitalEvidence evidence) {
        return evidences.findByIdForVisibility(evidence.getId()).orElseThrow();
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(EvidenceHashing.newContentDigest().digest(bytes));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> withoutVolatileFields(String body) {
        Map<String, Object> problem = new LinkedHashMap<>(JSON.readValue(body, Map.class));
        problem.remove("timestamp");
        problem.remove("instance");
        return problem;
    }

    private void assign(Operator... members) {
        for (Operator member : members) {
            memberships.save(CaseMembership.assign(owningCase, member, manager));
        }
        memberships.flush();
    }

    private Operator saveOperator(String username, OperatorRole role) {
        return operators.saveAndFlush(Operator.create(
                username,
                username + "@example.com",
                passwordEncoder.encode("correct-password"),
                "First",
                "Last",
                role));
    }

    private String bearer(Operator operator) {
        return "Bearer "
                + tokens.issue(operator.getId(), operator.getUsername(), operator.getRole())
                        .value();
    }

    private void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE custody_events");
        evidences.deleteAllInBatch();
        memberships.deleteAllInBatch();
        custodyCases.deleteAllInBatch();
        operators.deleteAllInBatch();
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
