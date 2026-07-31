package it.itsprodigi.proofchain.evidence.api;

import static it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFixtures.evidence;
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
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceRepository;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
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
 * HTTP contract of the canonical custody transfer endpoint: strict JSON, reason boundaries, Location header, sanitized
 * success body, indistinguishable holder conflicts and the documented OpenAPI operation.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustodyTransferWebMvcIT extends PostgreSqlIntegrationTest {

    private static final String TRANSFER_PATH = "/api/v1/evidences/{evidenceId}/transfer";
    private static final String HOLDER_NOT_ELIGIBLE = "https://proofchain.dev/problems/holder-not-eligible";
    private static final String VALIDATION_ERROR = "https://proofchain.dev/problems/validation-error";
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
    private JdbcTemplate jdbcTemplate;

    private Operator admin;
    private Operator manager;
    private Operator officer;
    private Operator otherOfficer;
    private Operator auditor;
    private Operator suspended;
    private Operator disabled;
    private Operator outsider;
    private CustodyCase owningCase;
    private DigitalEvidence target;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        admin = saveOperator("mvc-transfer-admin", OperatorRole.ADMIN);
        manager = saveOperator("mvc-transfer-manager", OperatorRole.CASE_MANAGER);
        officer = saveOperator("mvc-transfer-officer", OperatorRole.EVIDENCE_OFFICER);
        otherOfficer = saveOperator("mvc-transfer-other", OperatorRole.EVIDENCE_OFFICER);
        auditor = saveOperator("mvc-transfer-auditor", OperatorRole.AUDITOR);
        suspended = saveOperator("mvc-transfer-suspended", OperatorRole.EVIDENCE_OFFICER);
        disabled = saveOperator("mvc-transfer-disabled", OperatorRole.EVIDENCE_OFFICER);
        outsider = saveOperator("mvc-transfer-outsider", OperatorRole.CASE_MANAGER);
        owningCase = custodyCases.saveAndFlush(
                CustodyCase.create("Transfer HTTP case", null, null, null, null, CasePriority.HIGH, manager));
        assign(manager, officer, otherOfficer, auditor, suspended, disabled);
        changeStatus(suspended, OperatorStatus.SUSPENDED);
        changeStatus(disabled, OperatorStatus.DISABLED);
        target = evidences.saveAndFlush(evidence(owningCase, officer, "MVCTRANSFER"));
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void returnsTheCanonicalEventLocationAndASanitizedOperationResponse() throws Exception {
        MvcResult result = mockMvc.perform(transferRequest(target, manager, body(manager.getId(), "Handover.")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(2)))
                .andExpect(jsonPath("$.evidence.currentHolder.id")
                        .value(manager.getId().toString()))
                .andExpect(jsonPath("$.evidence.status").value("IN_CUSTODY"))
                .andExpect(jsonPath("$.evidence.storageKey").doesNotExist())
                .andExpect(jsonPath("$.evidence.version").doesNotExist())
                .andExpect(jsonPath("$.evidence.custodyEventCount").doesNotExist())
                .andExpect(jsonPath("$.evidence.custodyChainHeadHash").doesNotExist())
                .andExpect(jsonPath("$.eventSummary.eventType").value("CUSTODY_TRANSFERRED"))
                .andExpect(jsonPath("$.eventSummary.sequenceNumber").value(1))
                .andExpect(jsonPath("$.eventSummary.payload").doesNotExist())
                .andExpect(jsonPath("$.eventSummary.payloadJson").doesNotExist())
                .andReturn();

        Map<String, Object> response = readJson(result);
        String eventId = eventSummary(response).get("id").toString();
        assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo("/api/v1/evidences/%s/events/%s".formatted(target.getId(), eventId));
        assertThat(events.countByEvidenceId(target.getId())).isEqualTo(1L);
    }

    /**
     * Every ineligibility cause must be indistinguishable: the response body is byte-for-byte the same problem
     * document, so the existence, membership, status or role of the requested target can never be enumerated.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(IneligibleTarget.class)
    void mapsEveryHolderIneligibilityCauseToTheSameConflict(IneligibleTarget cause) throws Exception {
        MvcResult result = mockMvc.perform(transferRequest(target, manager, body(targetId(cause), "Handover.")))
                .andExpect(status().isConflict())
                .andReturn();

        Map<String, Object> problem = readJson(result);
        problem.remove("timestamp");
        assertThat(problem)
                .isEqualTo(Map.of(
                        "type",
                        HOLDER_NOT_ELIGIBLE,
                        "title",
                        "Evidence holder not eligible",
                        "status",
                        409,
                        "detail",
                        "The requested holder is not eligible for this custody case.",
                        "instance",
                        "/api/v1/evidences/" + target.getId() + "/transfer"));
        assertThat(events.countByEvidenceId(target.getId())).isZero();
    }

    @Test
    void rejectsUnknownPropertiesInvalidIdentifiersAndReasonBoundaries() throws Exception {
        String unknownProperty =
                "{\"newHolderId\":\"%s\",\"reason\":\"Handover.\",\"eventType\":\"CUSTODY_TRANSFERRED\"}"
                        .formatted(manager.getId());
        for (String invalid : new String[] {
            unknownProperty,
            "{\"reason\":\"Handover.\"}",
            "{\"newHolderId\":null,\"reason\":\"Handover.\"}",
            "{\"newHolderId\":\"not-a-uuid\",\"reason\":\"Handover.\"}",
            body(manager.getId(), "   "),
            body(manager.getId(), "x".repeat(1001))
        }) {
            mockMvc.perform(transferRequest(target, manager, invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(VALIDATION_ERROR));
        }
        assertThat(events.countByEvidenceId(target.getId())).isZero();

        DigitalEvidence shortest = evidences.saveAndFlush(evidence(owningCase, officer, "SHORT"));
        DigitalEvidence longest = evidences.saveAndFlush(evidence(owningCase, officer, "LONG"));

        mockMvc.perform(transferRequest(shortest, manager, body(manager.getId(), " x ")))
                .andExpect(status().isOk());
        mockMvc.perform(transferRequest(longest, manager, body(manager.getId(), "y".repeat(1000))))
                .andExpect(status().isOk());
        assertThat(events.countByEvidenceId(shortest.getId())).isEqualTo(1L);
        assertThat(events.countByEvidenceId(longest.getId())).isEqualTo(1L);
    }

    @Test
    void hidesEvidenceIdenticallyForNonMembersAndMissingIdentifiersAndForbidsVisibleAuditors() throws Exception {
        String hidden = mockMvc.perform(transferRequest(target, outsider, body(manager.getId(), "Handover.")))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String missing = mockMvc.perform(post(TRANSFER_PATH, UUID.randomUUID())
                        .header("Authorization", bearer(outsider))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(manager.getId(), "Handover.")))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(withoutVolatileFields(hidden)).isEqualTo(withoutVolatileFields(missing));
        mockMvc.perform(transferRequest(target, auditor, body(manager.getId(), "Handover.")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/access-denied"));
        mockMvc.perform(post(TRANSFER_PATH, target.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(manager.getId(), "Handover.")))
                .andExpect(status().isUnauthorized());
        assertThat(events.countByEvidenceId(target.getId())).isZero();
    }

    @Test
    void documentsTheCanonicalTransferOperationAndNoAlias() throws Exception {
        String operation = "$.paths['/api/v1/evidences/{evidenceId}/transfer'].post";
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(operation + ".operationId").value("transferEvidenceCustody"))
                .andExpect(jsonPath(operation + ".requestBody.content['application/json'].schema.$ref")
                        .value("#/components/schemas/TransferCustodyRequest"))
                .andExpect(jsonPath(operation + ".responses['200'].headers.Location")
                        .exists())
                .andExpect(jsonPath(operation + ".responses['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/EvidenceOperationResponse"))
                .andExpect(jsonPath(operation + ".responses['400']").exists())
                .andExpect(jsonPath(operation + ".responses['401']").exists())
                .andExpect(jsonPath(operation + ".responses['403']").exists())
                .andExpect(jsonPath(operation + ".responses['404']").exists())
                .andExpect(jsonPath(operation + ".responses['409']").exists())
                .andExpect(jsonPath(operation + ".responses['500']").exists())
                .andExpect(jsonPath("$.components.schemas.TransferCustodyRequest.additionalProperties")
                        .value(false))
                .andExpect(jsonPath("$.components.schemas.TransferCustodyRequest.properties.*", hasSize(2)))
                .andExpect(jsonPath("$.paths['/api/v1/cases/{caseId}/evidences/{evidenceId}/transfer']")
                        .doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/evidences/{evidenceId}/transfers']")
                        .doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/evidences/{evidenceId}/events'].post")
                        .doesNotExist());
    }

    enum IneligibleTarget {
        NONEXISTENT,
        NON_MEMBER,
        MEMBER_SUSPENDED,
        MEMBER_DISABLED,
        MEMBER_AUDITOR,
        GLOBAL_ADMIN_WITHOUT_MEMBERSHIP
    }

    private UUID targetId(IneligibleTarget cause) {
        return switch (cause) {
            case NONEXISTENT -> UUID.randomUUID();
            case NON_MEMBER -> outsider.getId();
            case MEMBER_SUSPENDED -> suspended.getId();
            case MEMBER_DISABLED -> disabled.getId();
            case MEMBER_AUDITOR -> auditor.getId();
            case GLOBAL_ADMIN_WITHOUT_MEMBERSHIP -> admin.getId();
        };
    }

    private MockHttpServletRequestBuilder transferRequest(DigitalEvidence evidence, Operator actor, String content) {
        return post(TRANSFER_PATH, evidence.getId())
                .header("Authorization", bearer(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content(content);
    }

    private static String body(UUID newHolderId, String reason) {
        return "{\"newHolderId\":\"%s\",\"reason\":\"%s\"}".formatted(newHolderId, reason);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(MvcResult result) throws Exception {
        return new LinkedHashMap<>(JSON.readValue(result.getResponse().getContentAsString(), Map.class));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> eventSummary(Map<String, Object> response) {
        return (Map<String, Object>) response.get("eventSummary");
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

    private void changeStatus(Operator operator, OperatorStatus status) {
        Operator managed = operators.findById(operator.getId()).orElseThrow();
        managed.changeStatus(status);
        operators.saveAndFlush(managed);
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
