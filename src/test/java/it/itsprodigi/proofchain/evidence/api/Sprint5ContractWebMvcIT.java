package it.itsprodigi.proofchain.evidence.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cross-cutting reconciliation of the complete Sprint 5 contract against the rendered OpenAPI document.
 *
 * <p>The per-workflow suites already pin each operation in isolation. This suite pins the properties that only exist
 * across the whole surface: exactly five operational routes and no alias, generic command or event-write route anywhere
 * in the document; a documented {@code Location} header on every successful command; response schemas that expose no
 * storage key, chain anchor, optimistic version or entity internal; and declared Problem Detail statuses that match the
 * statuses the runtime can actually reach.
 */
@SpringBootTest
@AutoConfigureMockMvc
class Sprint5ContractWebMvcIT extends PostgreSqlIntegrationTest {

    private static final String TRANSFER = "/api/v1/evidences/{evidenceId}/transfer";
    private static final String METADATA = "/api/v1/evidences/{evidenceId}/metadata";
    private static final String VERIFY_INTEGRITY = "/api/v1/evidences/{evidenceId}/verify-integrity";
    private static final String SEAL = "/api/v1/evidences/{evidenceId}/seal";
    private static final String RELEASE = "/api/v1/evidences/{evidenceId}/release";

    /** The canonical surface: path, the single allowed method, and the operation identifier. */
    private static final Map<String, String[]> SPRINT_5_ROUTES = Map.of(
            TRANSFER, new String[] {"post", "transferEvidenceCustody"},
            METADATA, new String[] {"patch", "updateEvidenceMetadata"},
            VERIFY_INTEGRITY, new String[] {"post", "verifyEvidenceIntegrity"},
            SEAL, new String[] {"post", "sealEvidence"},
            RELEASE, new String[] {"post", "releaseEvidence"});

    /** Statuses the runtime can actually produce per route, verified against the workflow services and the handler. */
    private static final Map<String, List<String>> DECLARED_STATUSES = Map.of(
            TRANSFER, List.of("200", "400", "401", "403", "404", "409", "500"),
            METADATA, List.of("200", "400", "401", "403", "404", "409", "500"),
            VERIFY_INTEGRITY, List.of("200", "400", "401", "404", "409", "500"),
            SEAL, List.of("200", "400", "401", "403", "404", "409", "500"),
            RELEASE, List.of("200", "400", "401", "403", "404", "409", "500"));

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    private JsonNode document;

    @BeforeEach
    void loadOpenApiDocument() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        document = JSON.readTree(body);
    }

    @Test
    void exposesExactlyTheFiveOperationalRoutesAndNoAliasOrGenericCommandRoute() {
        JsonNode paths = document.get("paths");

        SPRINT_5_ROUTES.forEach((path, method) -> {
            assertThat(paths.has(path)).as("route %s must exist", path).isTrue();
            assertThat(paths.get(path).propertyNames())
                    .as("route %s must expose exactly one method", path)
                    .containsExactly(method[0]);
            assertThat(paths.get(path).get(method[0]).get("operationId").stringValue())
                    .isEqualTo(method[1]);
        });

        List<String> operationalPaths = new ArrayList<>();
        for (String path : paths.propertyNames()) {
            if (SPRINT_5_ROUTES.containsKey(path)) {
                operationalPaths.add(path);
            }
        }
        assertThat(operationalPaths).hasSize(5);

        // No alias, generic command, generic event-write, bulk, batch or lifecycle-restoration route on evidence.
        List<String> forbidden = new ArrayList<>();
        for (String path : paths.propertyNames()) {
            String lowered = path.toLowerCase(Locale.ROOT);
            if (!lowered.contains("/evidences")) {
                continue;
            }
            boolean shape = lowered.contains("/command")
                    || lowered.contains("/bulk")
                    || lowered.contains("/batch")
                    || lowered.contains("/unseal")
                    || lowered.contains("/reopen")
                    || lowered.contains("/restore")
                    || lowered.contains("/status")
                    || lowered.contains("/holder")
                    || lowered.endsWith("/integrity");
            if (shape) {
                forbidden.add(path);
            }
            // Custody events are append-only: only GET is allowed on any event route.
            if (lowered.contains("/events")) {
                assertThat(paths.get(path).propertyNames())
                        .as("event route %s must be read-only", path)
                        .containsExactly("get");
            }
            // The evidence collection and item resources are never arbitrarily mutable.
            if (lowered.equals("/api/v1/evidences/{evidenceid}")) {
                assertThat(paths.get(path).propertyNames())
                        .as("the evidence item resource must be read-only")
                        .containsExactly("get");
            }
        }
        assertThat(forbidden).isEmpty();
    }

    @Test
    void documentsTheCanonicalEventLocationOnEverySuccessfulCommand() {
        SPRINT_5_ROUTES.forEach((path, method) -> {
            JsonNode success = document.get("paths")
                    .get(path)
                    .get(method[0])
                    .get("responses")
                    .get("200");
            JsonNode location = success.get("headers").get("Location");
            assertThat(location)
                    .as("route %s must document a Location header", path)
                    .isNotNull();
            assertThat(location.get("required").booleanValue()).isTrue();
            assertThat(location.get("schema").get("format").stringValue()).isEqualTo("uri");
        });
    }

    @Test
    void declaresOnlyTheProblemDetailStatusesTheRuntimeCanReach() {
        DECLARED_STATUSES.forEach((path, statuses) -> {
            String method = SPRINT_5_ROUTES.get(path)[0];
            JsonNode responses = document.get("paths").get(path).get(method).get("responses");
            assertThat(responses.propertyNames())
                    .as("route %s response set", path)
                    .containsExactlyInAnyOrderElementsOf(statuses);
            for (String status : statuses) {
                if ("200".equals(status)) {
                    continue;
                }
                assertThat(responses.get(status).get("content").has("application/problem+json"))
                        .as("route %s status %s must be a Problem Detail", path, status)
                        .isTrue();
            }
        });
    }

    @Test
    void exposesNoStorageKeyChainAnchorOptimisticVersionOrEntityInternalInAnyCommandResponseSchema() {
        JsonNode schemas = document.get("components").get("schemas");

        assertThat(schemas.get("EvidenceOperationResponse").get("properties").propertyNames())
                .containsExactlyInAnyOrder("evidence", "eventSummary");

        Map<String, JsonNode> inspected = new LinkedHashMap<>();
        inspected.put("EvidenceResponse", schemas.get("EvidenceResponse"));
        inspected.put("IntegrityVerificationResponse", schemas.get("IntegrityVerificationResponse"));
        inspected.put("CustodyEventSummaryResponse", schemas.get("CustodyEventSummaryResponse"));
        inspected.put("EvidenceOperatorSummaryResponse", schemas.get("EvidenceOperatorSummaryResponse"));

        List<String> leaks = List.of(
                "storageKey",
                "storagePath",
                "absolutePath",
                "version",
                "custodyEventCount",
                "custodyChainHeadHash",
                "custodyCase",
                "passwordHash",
                "payloadJson",
                "normalizedReferenceTag");
        inspected.forEach((name, schema) -> {
            assertThat(schema).as("schema %s must be published", name).isNotNull();
            List<String> properties = new ArrayList<>(schema.get("properties").propertyNames());
            assertThat(properties)
                    .as("schema %s must not expose internals", name)
                    .doesNotContainAnyElementsOf(leaks);
        });

        assertThat(inspected.get("EvidenceResponse").get("properties").propertyNames())
                .containsExactlyInAnyOrder(
                        "id",
                        "caseId",
                        "referenceTag",
                        "title",
                        "description",
                        "status",
                        "currentHolder",
                        "uploadedBy",
                        "createdAt",
                        "updatedAt",
                        "sourceType",
                        "sourceDescription",
                        "sourceManufacturer",
                        "sourceModel",
                        "sourceSerialNumber",
                        "sourceLogicalIdentifier",
                        "acquisitionMethod",
                        "acquiredAt",
                        "acquisitionLocation",
                        "acquisitionToolName",
                        "acquisitionToolVersion",
                        "acquisitionNotes",
                        "originalFilename",
                        "fileExtension",
                        "mediaType",
                        "fileSize",
                        "contentSha256",
                        "contextualSha256");
    }

    @Test
    void rendersEveryDocumentedExampleAsValidJsonForItsOperation() {
        int examples = 0;
        for (Map.Entry<String, String[]> route : SPRINT_5_ROUTES.entrySet()) {
            JsonNode operation = document.get("paths").get(route.getKey()).get(route.getValue()[0]);
            List<JsonNode> contents = new ArrayList<>();
            JsonNode requestBody = operation.get("requestBody");
            if (requestBody != null) {
                requestBody.get("content").forEach(contents::add);
            }
            JsonNode responses = operation.get("responses");
            for (String status : responses.propertyNames()) {
                JsonNode content = responses.get(status).get("content");
                if (content != null) {
                    content.forEach(contents::add);
                }
            }
            for (JsonNode content : contents) {
                JsonNode declared = content.get("examples");
                if (declared == null) {
                    continue;
                }
                for (String name : declared.propertyNames()) {
                    JsonNode value = declared.get(name).get("value");
                    assertThat(value)
                            .as("example %s of %s must carry a value", name, route.getKey())
                            .isNotNull();
                    JsonNode parsed = value.isString() ? JSON.readTree(value.stringValue()) : value;
                    assertThat(parsed.isObject())
                            .as("example %s of %s must render as a JSON object", name, route.getKey())
                            .isTrue();
                    examples++;
                }
            }
        }
        assertThat(examples)
                .as("the operational routes must document realistic examples")
                .isGreaterThanOrEqualTo(15);
    }

    @Test
    void rejectsUnknownPropertiesInEveryCommandRequestSchema() {
        JsonNode schemas = document.get("components").get("schemas");
        for (String name : List.of(
                "TransferCustodyRequest",
                "PatchEvidenceMetadataRequest",
                "SealEvidenceRequest",
                "ReleaseEvidenceRequest")) {
            JsonNode schema = schemas.get(name);
            assertThat(schema).as("request schema %s must be published", name).isNotNull();
            assertThat(schema.get("additionalProperties").booleanValue())
                    .as("request schema %s must reject unknown properties", name)
                    .isFalse();
        }
        // Integrity verification takes no request body at all.
        assertThat(document.get("paths").get(VERIFY_INTEGRITY).get("post").get("requestBody"))
                .isNull();
    }
}
