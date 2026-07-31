package it.itsprodigi.proofchain.evidence.api;

import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.evidence;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP contract of the two canonical evidence lifecycle endpoints: strict JSON, reason boundaries, Location header,
 * sanitized success bodies, the narrower release authorization, the terminal behavior of {@code RELEASED} across every
 * Sprint 5 command and the documented OpenAPI operations without any alias.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EvidenceLifecycleWebMvcIT extends PostgreSqlIntegrationTest {

    private static final String SEAL_PATH = "/api/v1/evidences/{evidenceId}/seal";
    private static final String RELEASE_PATH = "/api/v1/evidences/{evidenceId}/release";
    private static final String VALIDATION_ERROR = "https://proofchain.dev/problems/validation-error";
    private static final String INVALID_EVIDENCE_STATE = "https://proofchain.dev/problems/invalid-evidence-state";
    private static final String ACCESS_DENIED = "https://proofchain.dev/problems/access-denied";
    private static final byte[] CONTENT = "proofchain-lifecycle-🔒".getBytes(StandardCharsets.UTF_8);
    private static final JsonMapper JSON = JsonMapper.builder().build();

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

    private Operator manager;
    private Operator officer;
    private Operator otherOfficer;
    private Operator auditor;
    private Operator outsider;
    private CustodyCase owningCase;
    private DigitalEvidence target;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        manager = saveOperator("mvc-lifecycle-manager", OperatorRole.CASE_MANAGER);
        officer = saveOperator("mvc-lifecycle-officer", OperatorRole.EVIDENCE_OFFICER);
        otherOfficer = saveOperator("mvc-lifecycle-other", OperatorRole.EVIDENCE_OFFICER);
        auditor = saveOperator("mvc-lifecycle-auditor", OperatorRole.AUDITOR);
        outsider = saveOperator("mvc-lifecycle-outsider", OperatorRole.CASE_MANAGER);
        owningCase = custodyCases.saveAndFlush(
                CustodyCase.create("Lifecycle HTTP case", null, null, null, null, CasePriority.HIGH, manager));
        assign(manager, officer, otherOfficer, auditor);
        target = evidences.saveAndFlush(evidence(owningCase, officer, "MVCLIFECYCLE"));
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void returnsTheCanonicalEventLocationAndSanitizedBodiesForSealAndRelease() throws Exception {
        MvcResult sealed = mockMvc.perform(
                        lifecycleRequest(SEAL_PATH, target, manager, reason("Sealed for preservation.")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(2)))
                .andExpect(jsonPath("$.evidence.status").value("SEALED"))
                .andExpect(jsonPath("$.evidence.currentHolder.id")
                        .value(officer.getId().toString()))
                .andExpect(jsonPath("$.evidence.storageKey").doesNotExist())
                .andExpect(jsonPath("$.evidence.version").doesNotExist())
                .andExpect(jsonPath("$.evidence.custodyEventCount").doesNotExist())
                .andExpect(jsonPath("$.evidence.custodyChainHeadHash").doesNotExist())
                .andExpect(jsonPath("$.eventSummary.eventType").value("EVIDENCE_SEALED"))
                .andExpect(jsonPath("$.eventSummary.sequenceNumber").value(1))
                .andExpect(jsonPath("$.eventSummary.payload").doesNotExist())
                .andExpect(jsonPath("$.eventSummary.payloadJson").doesNotExist())
                .andReturn();

        MvcResult released = mockMvc.perform(
                        lifecycleRequest(RELEASE_PATH, target, manager, reason("Custody terminated.")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence.status").value("RELEASED"))
                .andExpect(jsonPath("$.evidence.currentHolder").isEmpty())
                .andExpect(jsonPath("$.eventSummary.eventType").value("EVIDENCE_RELEASED"))
                .andExpect(jsonPath("$.eventSummary.sequenceNumber").value(2))
                .andReturn();

        assertThat(location(sealed)).isEqualTo(expectedLocation(sealed));
        assertThat(location(released)).isEqualTo(expectedLocation(released));
        assertThat(events.countByEvidenceId(target.getId())).isEqualTo(2L);
    }

    @Test
    void rejectsUnknownPropertiesAndEnforcesTheExactReasonBoundariesOnBothCommands() throws Exception {
        for (String invalid : new String[] {
            "{\"reason\":\"Sealed.\",\"status\":\"SEALED\"}",
            "{\"reason\":\"Sealed.\",\"holderId\":\"" + officer.getId() + "\"}",
            "{\"reason\":\"Sealed.\",\"occurredAt\":\"2026-07-30T09:15:00Z\"}",
            "{}",
            "{\"reason\":null}",
            "{\"reason\":\"   \"}",
            reason("x".repeat(1001))
        }) {
            mockMvc.perform(lifecycleRequest(SEAL_PATH, target, manager, invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(VALIDATION_ERROR));
            mockMvc.perform(lifecycleRequest(RELEASE_PATH, target, manager, invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(VALIDATION_ERROR));
        }
        assertThat(events.countByEvidenceId(target.getId())).isZero();

        DigitalEvidence shortest = evidences.saveAndFlush(evidence(owningCase, officer, "SHORTREASON"));
        DigitalEvidence longest = evidences.saveAndFlush(evidence(owningCase, officer, "LONGREASON"));

        mockMvc.perform(lifecycleRequest(SEAL_PATH, shortest, manager, reason(" x ")))
                .andExpect(status().isOk());
        mockMvc.perform(lifecycleRequest(RELEASE_PATH, longest, manager, reason("y".repeat(1000))))
                .andExpect(status().isOk());
        assertThat(events.countByEvidenceId(shortest.getId())).isEqualTo(1L);
        assertThat(events.countByEvidenceId(longest.getId())).isEqualTo(1L);
    }

    @Test
    void hidesEvidenceIdenticallyAndForbidsAuditorsAndEveryEvidenceOfficerOnRelease() throws Exception {
        String hidden = mockMvc.perform(lifecycleRequest(SEAL_PATH, target, outsider, reason("Sealed.")))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String missing = mockMvc.perform(post(SEAL_PATH, UUID.randomUUID())
                        .header("Authorization", bearer(outsider))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reason("Sealed.")))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(withoutVolatileFields(hidden)).isEqualTo(withoutVolatileFields(missing));

        mockMvc.perform(lifecycleRequest(SEAL_PATH, target, auditor, reason("Sealed.")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(ACCESS_DENIED));
        mockMvc.perform(lifecycleRequest(SEAL_PATH, target, otherOfficer, reason("Sealed.")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(ACCESS_DENIED));
        for (Operator forbidden : new Operator[] {auditor, officer, otherOfficer}) {
            mockMvc.perform(lifecycleRequest(RELEASE_PATH, target, forbidden, reason("Released.")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value(ACCESS_DENIED));
        }
        mockMvc.perform(post(RELEASE_PATH, target.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reason("Released.")))
                .andExpect(status().isUnauthorized());
        assertThat(events.countByEvidenceId(target.getId())).isZero();
    }

    @Test
    void refusesBothCommandsInAClosedCase() throws Exception {
        owningCase.close();
        custodyCases.saveAndFlush(owningCase);

        for (String path : new String[] {SEAL_PATH, RELEASE_PATH}) {
            mockMvc.perform(lifecycleRequest(path, target, manager, reason("Too late.")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/case-closed"));
        }
        assertThat(events.countByEvidenceId(target.getId())).isZero();
    }

    /**
     * The complete terminal contract of {@code RELEASED}: every mutating Sprint 5 command is refused with the same
     * lifecycle conflict, while integrity verification and every read API keep working while the case is open. No row
     * of this matrix may restore {@code IN_CUSTODY} or {@code SEALED} or give the evidence a holder again.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(TerminalCommand.class)
    void releasedEvidenceRefusesEveryMutationButKeepsReadsAndVerification(TerminalCommand command) throws Exception {
        DigitalEvidence released = storedEvidence();
        mockMvc.perform(lifecycleRequest(RELEASE_PATH, released, manager, reason("Custody terminated.")))
                .andExpect(status().isOk());
        long eventsAfterRelease = events.countByEvidenceId(released.getId());

        var performed = mockMvc.perform(request(command, released)).andExpect(status().is(command.expectedStatus));
        if (command.expectedStatus == 409) {
            performed.andExpect(jsonPath("$.type").value(INVALID_EVIDENCE_STATE));
        }

        DigitalEvidence reloaded =
                evidences.findByIdForVisibility(released.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(EvidenceStatus.RELEASED);
        assertThat(reloaded.getCurrentHolder()).isNull();
        assertThat(events.countByEvidenceId(released.getId()))
                .isEqualTo(command.appendsEvent ? eventsAfterRelease + 1 : eventsAfterRelease);
    }

    @Test
    void documentsTheTwoCanonicalLifecycleOperationsAndNoAliasOrGenericTransition() throws Exception {
        String seal = "$.paths['/api/v1/evidences/{evidenceId}/seal'].post";
        String release = "$.paths['/api/v1/evidences/{evidenceId}/release'].post";
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(seal + ".operationId").value("sealEvidence"))
                .andExpect(jsonPath(seal + ".requestBody.content['application/json'].schema.$ref")
                        .value("#/components/schemas/SealEvidenceRequest"))
                .andExpect(jsonPath(seal + ".responses['200'].headers.Location").exists())
                .andExpect(jsonPath(seal + ".responses['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/EvidenceOperationResponse"))
                .andExpect(jsonPath(release + ".operationId").value("releaseEvidence"))
                .andExpect(jsonPath(release + ".requestBody.content['application/json'].schema.$ref")
                        .value("#/components/schemas/ReleaseEvidenceRequest"))
                .andExpect(
                        jsonPath(release + ".responses['200'].headers.Location").exists())
                .andExpect(jsonPath(release + ".responses['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/EvidenceOperationResponse"))
                .andExpect(jsonPath("$.components.schemas.SealEvidenceRequest.additionalProperties")
                        .value(false))
                .andExpect(jsonPath("$.components.schemas.SealEvidenceRequest.properties.*", hasSize(1)))
                .andExpect(jsonPath("$.components.schemas.ReleaseEvidenceRequest.additionalProperties")
                        .value(false))
                .andExpect(jsonPath("$.components.schemas.ReleaseEvidenceRequest.properties.*", hasSize(1)))
                .andExpect(jsonPath("$.paths['/api/v1/evidences/{evidenceId}/seal'].patch")
                        .doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/evidences/{evidenceId}/seal'].delete")
                        .doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/evidences/{evidenceId}/unseal']")
                        .doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/evidences/{evidenceId}/reopen']")
                        .doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/evidences/{evidenceId}/status']")
                        .doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/evidences/{evidenceId}/transitions']")
                        .doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/cases/{caseId}/evidences/{evidenceId}/seal']")
                        .doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/cases/{caseId}/evidences/{evidenceId}/release']")
                        .doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/evidences/seal']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/evidences/release']").doesNotExist());
    }

    enum TerminalCommand {
        TRANSFER(409, false),
        METADATA_UPDATE(409, false),
        SEAL(409, false),
        RELEASE_AGAIN(409, false),
        VERIFY_INTEGRITY(200, true),
        READ(200, false),
        LIST(200, false),
        DOWNLOAD(200, false),
        TIMELINE(200, false),
        VERIFY_CHAIN(200, false);

        private final int expectedStatus;
        private final boolean appendsEvent;

        TerminalCommand(int expectedStatus, boolean appendsEvent) {
            this.expectedStatus = expectedStatus;
            this.appendsEvent = appendsEvent;
        }
    }

    private MockHttpServletRequestBuilder request(TerminalCommand command, DigitalEvidence released) {
        UUID id = released.getId();
        return switch (command) {
            case TRANSFER ->
                post("/api/v1/evidences/{evidenceId}/transfer", id)
                        .header("Authorization", bearer(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newHolderId\":\"%s\",\"reason\":\"Reassign.\"}".formatted(otherOfficer.getId()));
            case METADATA_UPDATE ->
                patch("/api/v1/evidences/{evidenceId}/metadata", id)
                        .header("Authorization", bearer(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Renamed after release\",\"reason\":\"Correction.\"}");
            case SEAL -> lifecycleRequest(SEAL_PATH, released, manager, reason("Seal after release."));
            case RELEASE_AGAIN -> lifecycleRequest(RELEASE_PATH, released, manager, reason("Release again."));
            case VERIFY_INTEGRITY ->
                post("/api/v1/evidences/{evidenceId}/verify-integrity", id).header("Authorization", bearer(manager));
            case READ -> get("/api/v1/evidences/{evidenceId}", id).header("Authorization", bearer(manager));
            case LIST ->
                get("/api/v1/cases/{caseId}/evidences", owningCase.getId()).header("Authorization", bearer(manager));
            case DOWNLOAD ->
                get("/api/v1/evidences/{evidenceId}/download", id).header("Authorization", bearer(manager));
            case TIMELINE -> get("/api/v1/evidences/{evidenceId}/events", id).header("Authorization", bearer(manager));
            case VERIFY_CHAIN ->
                post("/api/v1/evidences/{evidenceId}/verify-chain", id).header("Authorization", bearer(manager));
        };
    }

    /** Evidence whose exact bytes really exist under the storage root, so download and verification can succeed. */
    private DigitalEvidence storedEvidence() {
        String storageKey = EvidenceStorageKeyFactory.forEvidence(owningCase.getId(), UUID.randomUUID());
        StagedEvidence staged = storage.stage(storageKey, new ByteArrayInputStream(CONTENT));
        storage.finalizeStaged(staged);
        return evidences.saveAndFlush(DigitalEvidence.create(
                owningCase,
                officer,
                officer,
                "MVCTERMINAL",
                "Terminal evidence",
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
                "terminal.bin",
                "application/octet-stream",
                staged.byteCount(),
                staged.contentSha256(),
                "0".repeat(64),
                storageKey));
    }

    private MockHttpServletRequestBuilder lifecycleRequest(
            String path, DigitalEvidence evidence, Operator actor, String content) {
        return post(path, evidence.getId())
                .header("Authorization", bearer(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content(content);
    }

    private static String reason(String reason) {
        return "{\"reason\":\"%s\"}".formatted(reason);
    }

    private static String location(MvcResult result) {
        return result.getResponse().getHeader("Location");
    }

    @SuppressWarnings("unchecked")
    private static String expectedLocation(MvcResult result) throws Exception {
        Map<String, Object> response =
                new LinkedHashMap<>(JSON.readValue(result.getResponse().getContentAsString(), Map.class));
        Map<String, Object> summary = (Map<String, Object>) response.get("eventSummary");
        return "/api/v1/evidences/%s/events/%s".formatted(summary.get("evidenceId"), summary.get("id"));
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
}
