package it.itsprodigi.proofchain.custodycase.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.itsprodigi.proofchain.auth.application.JwtTokenService;
import it.itsprodigi.proofchain.custodycase.domain.CaseMembership;
import it.itsprodigi.proofchain.custodycase.domain.CasePriority;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.custodycase.persistence.CustodyCaseRepository;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class CaseControllerWebMvcTest extends PostgreSqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService tokens;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private OperatorRepository operators;

    @MockitoSpyBean
    private CustodyCaseRepository custodyCases;

    @Autowired
    private CaseMembershipRepository memberships;

    private Operator admin;
    private Operator manager;
    private Operator auditor;
    private Operator evidenceOfficer;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        admin = operators.saveAndFlush(operator("admin", OperatorRole.ADMIN));
        manager = operators.saveAndFlush(operator("manager", OperatorRole.CASE_MANAGER));
        auditor = operators.saveAndFlush(operator("auditor", OperatorRole.AUDITOR));
        evidenceOfficer = operators.saveAndFlush(operator("evidence", OperatorRole.EVIDENCE_OFFICER));
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    private void cleanDatabase() {
        memberships.deleteAll();
        custodyCases.deleteAll();
        operators.deleteAll();
    }

    @Test
    void adminAndCaseManagerCreateAtomicallyWithLocationAndExactResponse() throws Exception {
        mockMvc.perform(post("/api/v1/cases")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("  Admin case  ")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern("https?://[^/]+/api/v1/cases/[0-9a-f-]{36}")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.*", hasSize(12)))
                .andExpect(jsonPath("$.title").value("Admin case"))
                .andExpect(jsonPath("$.description").value("Description"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.createdBy.id").value(admin.getId().toString()))
                .andExpect(jsonPath("$.createdBy.email").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist());

        mockMvc.perform(post("/api/v1/cases")
                        .header("Authorization", bearer(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("Manager case")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdBy.role").value("CASE_MANAGER"));

        assertThatEachCaseHasCreatorMembership();
    }

    @Test
    void nonManagerCreationIs403AndUnknownOrInvalidFieldsAre400() throws Exception {
        mockMvc.perform(post("/api/v1/cases")
                        .header("Authorization", bearer(auditor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("Forbidden case")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/access-denied"));

        mockMvc.perform(post("/api/v1/cases")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("Case title")
                                .replace("\"priority\": \"HIGH\"", "\"priority\": \"HIGH\", \"id\": \"x\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/validation-error"));
        mockMvc.perform(post("/api/v1/cases")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(" x ")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listUsesExactPageEnvelopeFixedOrderAndMembershipVisibility() throws Exception {
        caseWithMembership("Manager visible", manager);
        CustodyCase auditorCase = caseWithMembership("Auditor visible", auditor);
        caseWithMembership("Admin only", admin);

        mockMvc.perform(get("/api/v1/cases").header("Authorization", bearer(auditor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(5)))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(auditorCase.getId().toString()))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.sort").doesNotExist());

        mockMvc.perform(get("/api/v1/cases")
                        .header("Authorization", bearer(admin))
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].title").value("Admin only"))
                .andExpect(jsonPath("$.content[1].id").value(auditorCase.getId().toString()))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void invalidPaginationAndEverySortOccurrenceAre400() throws Exception {
        String token = bearer(admin);
        mockMvc.perform(get("/api/v1/cases").header("Authorization", token).param("page", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/cases").header("Authorization", token).param("size", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/cases").header("Authorization", token).param("size", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/cases").header("Authorization", token).param("sort", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/validation-error"));
    }

    @Test
    void detailHidesExistingNonMemberAndMissingCaseWithTheSame404() throws Exception {
        CustodyCase custodyCase = caseWithMembership("Manager case", manager);

        mockMvc.perform(get("/api/v1/cases/{caseId}", custodyCase.getId())
                        .header("Authorization", bearer(evidenceOfficer)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/resource-not-found"))
                .andExpect(jsonPath("$.detail").value("The requested resource was not found."));
        mockMvc.perform(get("/api/v1/cases/{caseId}", UUID.randomUUID())
                        .header("Authorization", bearer(evidenceOfficer)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/resource-not-found"))
                .andExpect(jsonPath("$.detail").value("The requested resource was not found."));
        mockMvc.perform(get("/api/v1/cases/{caseId}", custodyCase.getId()).header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
    }

    @Test
    void visibleReadOnlyMembersReceive403ButNonMemberManagerReceives404OnMutations() throws Exception {
        CustodyCase custodyCase = caseWithMembership("Audited case", auditor);
        String body = "{\"title\":\"Updated title\"}";

        mockMvc.perform(patch("/api/v1/cases/{caseId}", custodyCase.getId())
                        .header("Authorization", bearer(auditor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/access-denied"));
        mockMvc.perform(patch("/api/v1/cases/{caseId}", custodyCase.getId())
                        .header("Authorization", bearer(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/cases/{caseId}/status", custodyCase.getId())
                        .header("Authorization", bearer(auditor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminWithoutMembershipCanUpdateAndCloseAnExistingCase() throws Exception {
        CustodyCase custodyCase = caseWithMembership("Manager-owned case", manager);
        org.assertj.core.api.Assertions.assertThat(
                        memberships.existsByCustodyCaseIdAndOperatorId(custodyCase.getId(), admin.getId()))
                .isFalse();

        mockMvc.perform(patch("/api/v1/cases/{caseId}", custodyCase.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Admin updated case\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Admin updated case"));
        mockMvc.perform(patch("/api/v1/cases/{caseId}/status", custodyCase.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void metadataPatchPreservesAbsentFieldsClearsExplicitNullAndRejectsInvalidDocuments() throws Exception {
        CustodyCase custodyCase = caseWithMembership("Initial title", manager);
        String token = bearer(manager);

        mockMvc.perform(patch("/api/v1/cases/{caseId}", custodyCase.getId())
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":null,\"priority\":\"CRITICAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Initial title"))
                .andExpect(jsonPath("$.description").isEmpty())
                .andExpect(jsonPath("$.priority").value("CRITICAL"));

        for (String body : new String[] {
            "{}",
            "{\"title\":null}",
            "{\"title\":\"  \"}",
            "{\"priority\":null}",
            "{\"createdAt\":null}",
            "{\"unknown\":\"value\"}"
        }) {
            mockMvc.perform(patch("/api/v1/cases/{caseId}", custodyCase.getId())
                            .header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/validation-error"));
        }
    }

    @Test
    void closeIsIdempotentAndClosedCaseRejectsMetadataWithDedicated409() throws Exception {
        CustodyCase custodyCase = caseWithMembership("Closable case", manager);
        String token = bearer(manager);

        String first = mockMvc.perform(patch("/api/v1/cases/{caseId}/status", custodyCase.getId())
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String repeated = mockMvc.perform(patch("/api/v1/cases/{caseId}/status", custodyCase.getId())
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertSameClosureTimestamps(first, repeated);

        mockMvc.perform(patch("/api/v1/cases/{caseId}", custodyCase.getId())
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Cannot change\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/case-closed"));
    }

    @Test
    void openTargetIs409WhileInvalidNullMissingAndUnknownStatusDocumentsAre400() throws Exception {
        CustodyCase custodyCase = caseWithMembership("Status case", manager);
        String token = bearer(manager);
        String endpoint = "/api/v1/cases/{caseId}/status";

        mockMvc.perform(patch(endpoint, custodyCase.getId())
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/invalid-case-status-transition"));
        for (String body : new String[] {
            "{}", "{\"status\":null}", "{\"status\":\"ARCHIVED\"}", "{\"status\":\"CLOSED\",\"extra\":true}"
        }) {
            mockMvc.perform(patch(endpoint, custodyCase.getId())
                            .header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void optimisticConflictUsesTheStable409ProblemWithoutPersistenceDetails() throws Exception {
        CustodyCase custodyCase = caseWithMembership("Concurrent case", manager);
        doThrow(new OptimisticLockingFailureException("database detail must not leak"))
                .when(custodyCases)
                .flush();

        mockMvc.perform(patch("/api/v1/cases/{caseId}", custodyCase.getId())
                        .header("Authorization", bearer(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Updated title\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/concurrent-modification"))
                .andExpect(jsonPath("$.detail")
                        .value("The custody case was modified by another transaction. Retry using current data."));
    }

    @Test
    void anonymousAndInvalidUuidUseExistingSecurityAndValidationProblems() throws Exception {
        mockMvc.perform(get("/api/v1/cases"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/authentication-required"));
        mockMvc.perform(get("/api/v1/cases/not-a-uuid").header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/validation-error"));
    }

    @Test
    void undocumentedCaseRouteAliasesAreAbsentAtRuntime() throws Exception {
        String token = bearer(admin);
        UUID caseId = UUID.fromString("00000000-0000-4000-8000-000000000001");

        mockMvc.perform(post("/api/v1/custody-cases")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("Alias must not create")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/resource-not-found"));
        mockMvc.perform(get("/api/v1/case").header("Authorization", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/resource-not-found"));
        mockMvc.perform(get("/api/v1/cases/{caseId}/memberships", caseId).header("Authorization", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/resource-not-found"));
    }

    @Test
    void openApiDocumentsAllCaseOperationsSchemasStatusesAndSecurity() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/case']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/custody-cases']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/cases/{caseId}/memberships']")
                        .doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/cases'].post.operationId").value("createCustodyCase"))
                .andExpect(jsonPath("$.paths['/api/v1/cases'].post.tags[0]").value("Custody cases"))
                .andExpect(jsonPath("$.paths['/api/v1/cases'].post.security[0].bearerAuth")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/v1/cases'].post.requestBody.required")
                        .value(true))
                .andExpect(jsonPath(
                                "$.paths['/api/v1/cases'].post.requestBody.content['application/json'].examples.case.value.title")
                        .value("Mobile device seizure"))
                .andExpect(jsonPath("$.paths['/api/v1/cases'].post.responses['201'].content['application/json']")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/v1/cases'].post.responses['201'].headers.Location.required")
                        .value(true))
                .andExpect(jsonPath("$.paths['/api/v1/cases'].post.responses['201'].headers.Location.schema.type")
                        .value("string"))
                .andExpect(jsonPath("$.paths['/api/v1/cases'].post.responses['201'].headers.Location.schema.format")
                        .value("uri"))
                .andExpect(
                        jsonPath("$.paths['/api/v1/cases'].post.responses['400'].content['application/problem+json']")
                                .exists())
                .andExpect(jsonPath("$.paths['/api/v1/cases'].post.responses")
                        .value(org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.hasKey("201"),
                                org.hamcrest.Matchers.hasKey("400"),
                                org.hamcrest.Matchers.hasKey("401"),
                                org.hamcrest.Matchers.hasKey("403"))))
                .andExpect(jsonPath("$.paths['/api/v1/cases'].get.responses['200'].content['application/json']")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/v1/cases/{caseId}'].get.operationId")
                        .value("getCustodyCase"))
                .andExpect(jsonPath("$.paths['/api/v1/cases/{caseId}'].get.parameters[0].example")
                        .value("1ca01c67-75b9-48e3-a2ed-72259373c67c"))
                .andExpect(jsonPath(
                                "$.paths['/api/v1/cases/{caseId}'].get.responses['404'].content['application/problem+json']")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/v1/cases/{caseId}'].patch.requestBody.required")
                        .value(true))
                .andExpect(jsonPath(
                                "$.paths['/api/v1/cases/{caseId}'].patch.requestBody.content['application/json'].examples.metadata.value.priority")
                        .value("CRITICAL"))
                .andExpect(jsonPath(
                                "$.paths['/api/v1/cases/{caseId}'].patch.responses['403'].content['application/problem+json']")
                        .exists())
                .andExpect(jsonPath(
                                "$.paths['/api/v1/cases/{caseId}'].patch.responses['409'].content['application/problem+json']")
                        .exists())
                .andExpect(jsonPath(
                                "$.paths['/api/v1/cases/{caseId}/status'].patch.responses['409'].content['application/problem+json']")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/v1/cases/{caseId}/status'].patch.requestBody.required")
                        .value(true))
                .andExpect(jsonPath(
                                "$.paths['/api/v1/cases/{caseId}/status'].patch.requestBody.content['application/json'].examples.close.value.status")
                        .value("CLOSED"))
                .andExpect(jsonPath("$.components.schemas.CaseResponse.properties.version")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.CaseResponse.description")
                        .value("Custody case representation without persistence locking metadata."))
                .andExpect(jsonPath("$.components.schemas.CaseResponse.properties.id.format")
                        .value("uuid"))
                .andExpect(jsonPath("$.components.schemas.CaseResponse.properties.closedAt.type")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("string", "null")))
                .andExpect(jsonPath("$.components.schemas.CaseResponse.required")
                        .value(org.hamcrest.Matchers.containsInAnyOrder(
                                "id",
                                "title",
                                "description",
                                "authorityName",
                                "externalReference",
                                "location",
                                "priority",
                                "status",
                                "createdBy",
                                "createdAt",
                                "updatedAt",
                                "closedAt")))
                .andExpect(jsonPath("$.components.schemas.CaseOperatorSummaryResponse.properties.email")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.CasePageResponse.properties.sort")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.CreateCaseRequest.additionalProperties")
                        .value(false))
                .andExpect(jsonPath(
                        "$.components.schemas.CreateCaseRequest.required",
                        org.hamcrest.Matchers.containsInAnyOrder("title", "priority")))
                .andExpect(jsonPath("$.components.schemas.CreateCaseRequest.properties.title.minLength")
                        .value(3))
                .andExpect(jsonPath("$.components.schemas.CreateCaseRequest.properties.title.maxLength")
                        .value(200))
                .andExpect(jsonPath("$.components.schemas.CreateCaseRequest.properties.title.example")
                        .value("Mobile device seizure"))
                .andExpect(jsonPath("$.components.schemas.CreateCaseRequest.properties.description.maxLength")
                        .value(2000))
                .andExpect(jsonPath("$.components.schemas.CreateCaseRequest.properties.location.maxLength")
                        .value(300))
                .andExpect(jsonPath("$.components.schemas.PatchCaseMetadataRequest.additionalProperties")
                        .value(false))
                .andExpect(jsonPath("$.components.schemas.PatchCaseMetadataRequest.minProperties")
                        .value(1))
                .andExpect(jsonPath("$.components.schemas.PatchCaseMetadataRequest.required")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.PatchCaseMetadataRequest.properties.title.minLength")
                        .value(3))
                .andExpect(jsonPath("$.components.schemas.PatchCaseMetadataRequest.properties.title.maxLength")
                        .value(200))
                .andExpect(jsonPath("$.components.schemas.CreateCaseRequest.properties.description.type")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("string", "null")))
                .andExpect(jsonPath("$.components.schemas.PatchCaseMetadataRequest.properties.title.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.PatchCaseMetadataRequest.properties.description.type")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("string", "null")))
                .andExpect(jsonPath("$.components.schemas.PatchCaseMetadataRequest.properties.priority.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.UpdateCaseStatusRequest.additionalProperties")
                        .value(false))
                .andExpect(jsonPath("$.components.schemas.UpdateCaseStatusRequest.required[0]")
                        .value("status"))
                .andExpect(jsonPath(
                        "$.components.schemas.UpdateCaseStatusRequest.properties.status.enum",
                        org.hamcrest.Matchers.containsInAnyOrder("OPEN", "CLOSED")));
    }

    private CustodyCase caseWithMembership(String title, Operator member) {
        CustodyCase custodyCase = custodyCases.saveAndFlush(
                CustodyCase.create(title, "Description", "Authority", "REF", "Rome", CasePriority.HIGH, admin));
        memberships.saveAndFlush(CaseMembership.assign(custodyCase, member, admin));
        return custodyCase;
    }

    private String bearer(Operator operator) {
        return "Bearer "
                + tokens.issue(operator.getId(), operator.getUsername(), operator.getRole())
                        .value();
    }

    private Operator operator(String username, OperatorRole role) {
        return Operator.create(
                username, username + "@example.com", passwordEncoder.encode("correct-password"), "First", "Last", role);
    }

    private static String createBody(String title) {
        return """
                {
                  "title": "%s",
                  "description": " Description ",
                  "authorityName": " Authority ",
                  "externalReference": " REF-42 ",
                  "location": " Rome ",
                  "priority": "HIGH"
                }
                """.formatted(title);
    }

    private void assertThatEachCaseHasCreatorMembership() {
        for (CustodyCase custodyCase : custodyCases.findAll()) {
            org.assertj.core.api.Assertions.assertThat(memberships.existsByCustodyCaseIdAndOperatorId(
                            custodyCase.getId(), custodyCase.getCreatedBy().getId()))
                    .isTrue();
        }
    }

    private static void assertSameClosureTimestamps(String first, String repeated) throws Exception {
        tools.jackson.databind.JsonNode firstJson =
                tools.jackson.databind.json.JsonMapper.builder().build().readTree(first);
        tools.jackson.databind.JsonNode repeatedJson =
                tools.jackson.databind.json.JsonMapper.builder().build().readTree(repeated);
        org.assertj.core.api.Assertions.assertThat(repeatedJson.get("closedAt")).isEqualTo(firstJson.get("closedAt"));
        org.assertj.core.api.Assertions.assertThat(repeatedJson.get("updatedAt"))
                .isEqualTo(firstJson.get("updatedAt"));
    }
}
