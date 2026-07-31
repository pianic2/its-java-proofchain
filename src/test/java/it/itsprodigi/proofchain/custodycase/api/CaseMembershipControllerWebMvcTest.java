package it.itsprodigi.proofchain.custodycase.api;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class CaseMembershipControllerWebMvcTest extends PostgreSqlIntegrationTest {

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

    private Operator admin;
    private Operator manager;
    private Operator otherManager;
    private Operator auditor;
    private Operator evidenceOfficer;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        admin = operators.saveAndFlush(operator("admin", OperatorRole.ADMIN));
        manager = operators.saveAndFlush(operator("manager", OperatorRole.CASE_MANAGER));
        otherManager = operators.saveAndFlush(operator("other-manager", OperatorRole.CASE_MANAGER));
        auditor = operators.saveAndFlush(operator("auditor", OperatorRole.AUDITOR));
        evidenceOfficer = operators.saveAndFlush(operator("evidence", OperatorRole.EVIDENCE_OFFICER));
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void listIsOrderedAndVisibleOnlyToAdminOrMembers() throws Exception {
        CustodyCase custodyCase = caseWithCreator(manager);
        memberships.saveAndFlush(CaseMembership.assign(custodyCase, auditor, manager));
        memberships.saveAndFlush(CaseMembership.assign(custodyCase, evidenceOfficer, manager));
        var expected = memberships.findAllByCustodyCaseIdOrderByAssignedAtAscIdAsc(custodyCase.getId()).stream()
                .map(membership -> membership.getId().toString())
                .toList();

        String body = mockMvc.perform(get("/api/v1/cases/{caseId}/members", custodyCase.getId())
                        .header("Authorization", bearer(auditor)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].caseId").value(custodyCase.getId().toString()))
                .andExpect(jsonPath("$[0].operator.email").doesNotExist())
                .andExpect(jsonPath("$[0].assignedBy.email").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(body);
        org.assertj.core.api.Assertions.assertThat(json)
                .extracting(node -> node.get("id").stringValue())
                .containsExactlyElementsOf(expected);

        mockMvc.perform(get("/api/v1/cases/{caseId}/members", custodyCase.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/cases/{caseId}/members", custodyCase.getId())
                        .header("Authorization", bearer(otherManager)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/resource-not-found"));
    }

    @Test
    void assignmentIsCreatedThenIdempotentAndPreservesOriginalMetadata() throws Exception {
        CustodyCase custodyCase = caseWithCreator(manager);
        String first = mockMvc.perform(
                        put("/api/v1/cases/{caseId}/members/{operatorId}", custodyCase.getId(), auditor.getId())
                                .header("Authorization", bearer(manager)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.*", hasSize(5)))
                .andExpect(jsonPath("$.operator.id").value(auditor.getId().toString()))
                .andExpect(jsonPath("$.assignedBy.id").value(manager.getId().toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        auditor.changeStatus(OperatorStatus.SUSPENDED);
        operators.saveAndFlush(auditor);
        String repeated = mockMvc.perform(
                        put("/api/v1/cases/{caseId}/members/{operatorId}", custodyCase.getId(), auditor.getId())
                                .header("Authorization", bearer(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operator.status").value("SUSPENDED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var created = json.readTree(first);
        var existing = json.readTree(repeated);
        org.assertj.core.api.Assertions.assertThat(existing.get("id")).isEqualTo(created.get("id"));
        org.assertj.core.api.Assertions.assertThat(existing.get("assignedAt")).isEqualTo(created.get("assignedAt"));
        org.assertj.core.api.Assertions.assertThat(existing.get("assignedBy")).isEqualTo(created.get("assignedBy"));
        org.assertj.core.api.Assertions.assertThat(
                        memberships.findAllByCustodyCaseIdOrderByAssignedAtAscIdAsc(custodyCase.getId()))
                .hasSize(2);
    }

    @Test
    void newInactiveAndManualAdminAssignmentsUseDedicatedConflicts() throws Exception {
        CustodyCase custodyCase = caseWithCreator(manager);
        evidenceOfficer.changeStatus(OperatorStatus.DISABLED);
        operators.saveAndFlush(evidenceOfficer);

        mockMvc.perform(put("/api/v1/cases/{caseId}/members/{operatorId}", custodyCase.getId(), evidenceOfficer.getId())
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/operator-not-active"));
        mockMvc.perform(put("/api/v1/cases/{caseId}/members/{operatorId}", custodyCase.getId(), admin.getId())
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/admin-membership-not-assignable"));

        CustodyCase adminCase = caseWithCreator(admin);
        mockMvc.perform(put("/api/v1/cases/{caseId}/members/{operatorId}", adminCase.getId(), admin.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operator.role").value("ADMIN"));
    }

    @Test
    void removalIsIdempotentAndAllowsSelfOrCreatorOnlyWhenTheInvariantRemains() throws Exception {
        CustodyCase custodyCase = caseWithCreator(manager);

        mockMvc.perform(delete("/api/v1/cases/{caseId}/members/{operatorId}", custodyCase.getId(), manager.getId())
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/last-case-manager-removal"));

        memberships.saveAndFlush(CaseMembership.assign(custodyCase, otherManager, manager));
        mockMvc.perform(delete("/api/v1/cases/{caseId}/members/{operatorId}", custodyCase.getId(), manager.getId())
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isNoContent());
        org.assertj.core.api.Assertions.assertThat(custodyCases
                        .findById(custodyCase.getId())
                        .orElseThrow()
                        .getCreatedBy()
                        .getId())
                .isEqualTo(manager.getId());

        mockMvc.perform(delete("/api/v1/cases/{caseId}/members/{operatorId}", custodyCase.getId(), UUID.randomUUID())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());
    }

    @Test
    void authorizationAndClosedStateFollowThe404_403_409Matrix() throws Exception {
        CustodyCase custodyCase = caseWithCreator(manager);
        memberships.saveAndFlush(CaseMembership.assign(custodyCase, auditor, manager));

        mockMvc.perform(put("/api/v1/cases/{caseId}/members/{operatorId}", custodyCase.getId(), evidenceOfficer.getId())
                        .header("Authorization", bearer(otherManager)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/cases/{caseId}/members/{operatorId}", custodyCase.getId(), manager.getId())
                        .header("Authorization", bearer(auditor)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/access-denied"));

        custodyCase.close();
        custodyCases.saveAndFlush(custodyCase);
        mockMvc.perform(get("/api/v1/cases/{caseId}/members", custodyCase.getId())
                        .header("Authorization", bearer(auditor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(put("/api/v1/cases/{caseId}/members/{operatorId}", custodyCase.getId(), auditor.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/case-closed"));
        mockMvc.perform(delete("/api/v1/cases/{caseId}/members/{operatorId}", custodyCase.getId(), UUID.randomUUID())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/case-closed"));
    }

    @Test
    void authenticatedMissingCasesAndMissingAssignmentTargetAreNotFound() throws Exception {
        UUID missingCaseId = UUID.randomUUID();
        UUID missingOperatorId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/cases/{caseId}/members", missingCaseId).header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/cases/{caseId}/members/{operatorId}", missingCaseId, auditor.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/cases/{caseId}/members/{operatorId}", missingCaseId, auditor.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound());

        CustodyCase custodyCase = caseWithCreator(manager);
        mockMvc.perform(put("/api/v1/cases/{caseId}/members/{operatorId}", custodyCase.getId(), missingOperatorId)
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminCreatorRemovalKeepsCreatorIdentityAndGlobalAdminAccess() throws Exception {
        CustodyCase custodyCase = caseWithCreator(admin);

        mockMvc.perform(delete("/api/v1/cases/{caseId}/members/{operatorId}", custodyCase.getId(), admin.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/last-case-manager-removal"));

        memberships.saveAndFlush(CaseMembership.assign(custodyCase, otherManager, admin));
        mockMvc.perform(delete("/api/v1/cases/{caseId}/members/{operatorId}", custodyCase.getId(), admin.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(
                        memberships.findByCaseIdAndOperatorId(custodyCase.getId(), admin.getId()))
                .isEmpty();
        org.assertj.core.api.Assertions.assertThat(custodyCases
                        .findById(custodyCase.getId())
                        .orElseThrow()
                        .getCreatedBy()
                        .getId())
                .isEqualTo(admin.getId());
        mockMvc.perform(get("/api/v1/cases/{caseId}/members", custodyCase.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(
                        jsonPath("$[0].operator.id").value(otherManager.getId().toString()));
    }

    @Test
    void invalidIdentifiersAuthenticationAndOpenApiAreControlled() throws Exception {
        mockMvc.perform(get("/api/v1/cases/{caseId}/members", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/cases/not-a-uuid/members/not-a-uuid").header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/validation-error"));

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/cases/{caseId}/members'].get.operationId")
                        .value("listCustodyCaseMembers"))
                .andExpect(jsonPath("$.paths['/api/v1/cases/{caseId}/members'].get.responses['200']")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/v1/cases/{caseId}/members'].get.parameters[0].example")
                        .value("1ca01c67-75b9-48e3-a2ed-72259373c67c"))
                .andExpect(jsonPath("$.paths['/api/v1/cases/{caseId}/members/{operatorId}'].put.responses['201']")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/v1/cases/{caseId}/members/{operatorId}'].put.responses")
                        .value(org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.hasKey("200"),
                                org.hamcrest.Matchers.hasKey("201"),
                                org.hamcrest.Matchers.hasKey("400"),
                                org.hamcrest.Matchers.hasKey("401"),
                                org.hamcrest.Matchers.hasKey("403"),
                                org.hamcrest.Matchers.hasKey("404"),
                                org.hamcrest.Matchers.hasKey("409"))))
                .andExpect(jsonPath(
                                "$.paths['/api/v1/cases/{caseId}/members/{operatorId}'].put.responses['409'].content['application/problem+json']")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/v1/cases/{caseId}/members/{operatorId}'].delete.responses['204']")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/v1/cases/{caseId}/members/{operatorId}'].put.parameters[1].example")
                        .value("9a3b8bf4-1d96-4a1e-810e-5a2f8b6ee2b1"))
                .andExpect(jsonPath("$.components.schemas.MembershipResponse.properties.version")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.MembershipResponse.properties.id.format")
                        .value("uuid"))
                .andExpect(jsonPath("$.components.schemas.MembershipResponse.properties.assignedAt.example")
                        .value("2026-07-29T08:15:30.123456Z"))
                .andExpect(jsonPath("$.components.schemas.MembershipResponse.required")
                        .value(org.hamcrest.Matchers.containsInAnyOrder(
                                "id", "caseId", "operator", "assignedBy", "assignedAt")))
                .andExpect(jsonPath("$.components.schemas.MembershipResponse.properties.operator.$ref")
                        .value("#/components/schemas/CaseOperatorSummaryResponse"));
    }

    @Test
    void operatorRoleAndStatusPatchesPreserveTheExistingOperatorInvariantProblemContract() throws Exception {
        caseWithCreator(manager);

        mockMvc.perform(patch("/api/v1/operators/{id}/role", manager.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"AUDITOR\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/operator-invariant-conflict"));
        mockMvc.perform(patch("/api/v1/operators/{id}/status", manager.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/operator-invariant-conflict"));

        org.assertj.core.api.Assertions.assertThat(
                        operators.findById(manager.getId()).orElseThrow())
                .satisfies(operator -> {
                    org.assertj.core.api.Assertions.assertThat(operator.getRole())
                            .isEqualTo(OperatorRole.CASE_MANAGER);
                    org.assertj.core.api.Assertions.assertThat(operator.getStatus())
                            .isEqualTo(OperatorStatus.ACTIVE);
                });
    }

    private CustodyCase caseWithCreator(Operator creator) {
        CustodyCase custodyCase = custodyCases.saveAndFlush(CustodyCase.create(
                creator.getUsername() + " case", null, null, null, null, CasePriority.HIGH, creator));
        memberships.saveAndFlush(CaseMembership.assign(custodyCase, creator, creator));
        return custodyCase;
    }

    private void cleanDatabase() {
        memberships.deleteAllInBatch();
        custodyCases.deleteAllInBatch();
        operators.deleteAllInBatch();
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
}
