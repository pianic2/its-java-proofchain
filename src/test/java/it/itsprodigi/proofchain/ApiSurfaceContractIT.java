package it.itsprodigi.proofchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.itsprodigi.proofchain.common.config.OpenApiConfig;
import it.itsprodigi.proofchain.common.config.SecurityConfig;
import it.itsprodigi.proofchain.common.exception.ProblemTypes;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The single authoritative allowlist for the published HTTP surface.
 *
 * <p>Every other API test pins one workflow. This one pins the surface itself, and it does so from the only place that
 * cannot drift: the {@link RequestMappingInfoHandlerMapping} beans Spring actually dispatches with — the application
 * controllers, the springdoc resources and the actuator endpoints alike. A controller added, renamed, aliased or
 * accidentally exposed changes that set and fails here before anything reaches the OpenAPI document.
 *
 * <p>The document is then reconciled against the same allowlist, operation by operation: method, path, operation
 * identifier, authentication requirement, request media type, response statuses, Problem Details media type and the
 * headers each success declares. The runtime problem catalogue, the absence of persistence internals in every published
 * schema, the synthetic nature of every example and the Postman package are checked against the same source of truth,
 * so the delivered collection can only exercise endpoints this list approves.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiSurfaceContractIT extends PostgreSqlIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String JSON_MEDIA_TYPE = "application/json";
    private static final String PROBLEM_TYPE_PREFIX = "https://proofchain.dev/problems/";

    /** Authentication requirement of a documented operation. */
    private enum Auth {
        PUBLIC,
        BEARER
    }

    /**
     * The approved endpoint. Path and method are matched against the live Spring mappings; everything else is matched
     * against the generated document.
     */
    private record Endpoint(
            String method,
            String path,
            String operationId,
            Auth auth,
            String requestMediaType,
            String successStatus,
            List<String> statuses,
            List<String> successHeaders) {

        String key() {
            return method + " " + path;
        }
    }

    private static Endpoint api(
            String method,
            String path,
            String operationId,
            Auth auth,
            String requestMediaType,
            String successStatus,
            String statuses,
            String successHeaders) {
        return new Endpoint(
                method,
                path,
                operationId,
                auth,
                requestMediaType,
                successStatus,
                List.of(statuses.split(",")),
                successHeaders.isEmpty() ? List.of() : List.of(successHeaders.split(",")));
    }

    /**
     * The complete approved API surface of Sprints 1–5. Adding a row is a contract decision; the build fails until the
     * runtime, the document and this table agree on every column.
     */
    private static final List<Endpoint> API = List.of(
            // Authentication
            api("POST", "/api/v1/auth/login", "login", Auth.PUBLIC, JSON_MEDIA_TYPE, "200", "200,400,401", ""),
            api("GET", "/api/v1/auth/me", "getCurrentOperator", Auth.BEARER, null, "200", "200,401", ""),
            // Operator administration
            api(
                    "POST",
                    "/api/v1/operators",
                    "createOperator",
                    Auth.BEARER,
                    JSON_MEDIA_TYPE,
                    "201",
                    "201,400,401,403,409",
                    "Location"),
            api("GET", "/api/v1/operators", "listOperators", Auth.BEARER, null, "200", "200,400,401,403", ""),
            api("GET", "/api/v1/operators/{id}", "getOperator", Auth.BEARER, null, "200", "200,400,401,403,404", ""),
            api(
                    "PATCH",
                    "/api/v1/operators/{id}/role",
                    "updateOperatorRole",
                    Auth.BEARER,
                    JSON_MEDIA_TYPE,
                    "200",
                    "200,400,401,403,404,409",
                    ""),
            api(
                    "PATCH",
                    "/api/v1/operators/{id}/status",
                    "updateOperatorStatus",
                    Auth.BEARER,
                    JSON_MEDIA_TYPE,
                    "200",
                    "200,400,401,403,404,409",
                    ""),
            // Custody cases
            api(
                    "POST",
                    "/api/v1/cases",
                    "createCustodyCase",
                    Auth.BEARER,
                    JSON_MEDIA_TYPE,
                    "201",
                    "201,400,401,403",
                    "Location"),
            api("GET", "/api/v1/cases", "listCustodyCases", Auth.BEARER, null, "200", "200,400,401", ""),
            api("GET", "/api/v1/cases/{caseId}", "getCustodyCase", Auth.BEARER, null, "200", "200,400,401,404", ""),
            api(
                    "PATCH",
                    "/api/v1/cases/{caseId}",
                    "updateCustodyCaseMetadata",
                    Auth.BEARER,
                    JSON_MEDIA_TYPE,
                    "200",
                    "200,400,401,403,404,409",
                    ""),
            api(
                    "PATCH",
                    "/api/v1/cases/{caseId}/status",
                    "closeCustodyCase",
                    Auth.BEARER,
                    JSON_MEDIA_TYPE,
                    "200",
                    "200,400,401,403,404,409",
                    ""),
            // Memberships
            api(
                    "GET",
                    "/api/v1/cases/{caseId}/members",
                    "listCustodyCaseMembers",
                    Auth.BEARER,
                    null,
                    "200",
                    "200,400,401,404",
                    ""),
            api(
                    "PUT",
                    "/api/v1/cases/{caseId}/members/{operatorId}",
                    "assignCustodyCaseMember",
                    Auth.BEARER,
                    null,
                    "200",
                    "200,201,400,401,403,404,409",
                    ""),
            api(
                    "DELETE",
                    "/api/v1/cases/{caseId}/members/{operatorId}",
                    "removeCustodyCaseMember",
                    Auth.BEARER,
                    null,
                    "204",
                    "204,400,401,403,404,409",
                    ""),
            // Evidence registration and read
            api(
                    "POST",
                    "/api/v1/cases/{caseId}/evidences",
                    "registerDigitalEvidence",
                    Auth.BEARER,
                    "multipart/form-data",
                    "201",
                    "201,400,401,403,404,409,413,500",
                    "Location"),
            api(
                    "GET",
                    "/api/v1/cases/{caseId}/evidences",
                    "listDigitalEvidence",
                    Auth.BEARER,
                    null,
                    "200",
                    "200,400,401,404",
                    ""),
            api(
                    "GET",
                    "/api/v1/evidences/{evidenceId}",
                    "getDigitalEvidence",
                    Auth.BEARER,
                    null,
                    "200",
                    "200,400,401,404",
                    ""),
            api(
                    "GET",
                    "/api/v1/evidences/{evidenceId}/download",
                    "downloadDigitalEvidence",
                    Auth.BEARER,
                    null,
                    "200",
                    "200,400,401,404,500",
                    "Content-Disposition,Content-Length"),
            // Custody event timeline and chain verification
            api(
                    "GET",
                    "/api/v1/evidences/{evidenceId}/events",
                    "listCustodyEvents",
                    Auth.BEARER,
                    null,
                    "200",
                    "200,400,401,404,500",
                    ""),
            api(
                    "GET",
                    "/api/v1/evidences/{evidenceId}/events/{eventId}",
                    "getCustodyEvent",
                    Auth.BEARER,
                    null,
                    "200",
                    "200,400,401,404,500",
                    ""),
            api(
                    "POST",
                    "/api/v1/evidences/{evidenceId}/verify-chain",
                    "verifyCustodyChain",
                    Auth.BEARER,
                    null,
                    "200",
                    "200,400,401,404,500",
                    ""),
            // Operational custody workflows
            api(
                    "POST",
                    "/api/v1/evidences/{evidenceId}/transfer",
                    "transferEvidenceCustody",
                    Auth.BEARER,
                    JSON_MEDIA_TYPE,
                    "200",
                    "200,400,401,403,404,409,500",
                    "Location"),
            api(
                    "PATCH",
                    "/api/v1/evidences/{evidenceId}/metadata",
                    "updateEvidenceMetadata",
                    Auth.BEARER,
                    JSON_MEDIA_TYPE,
                    "200",
                    "200,400,401,403,404,409,500",
                    "Location"),
            api(
                    "POST",
                    "/api/v1/evidences/{evidenceId}/verify-integrity",
                    "verifyEvidenceIntegrity",
                    Auth.BEARER,
                    null,
                    "200",
                    "200,400,401,404,409,500",
                    "Location"),
            api(
                    "POST",
                    "/api/v1/evidences/{evidenceId}/seal",
                    "sealEvidence",
                    Auth.BEARER,
                    JSON_MEDIA_TYPE,
                    "200",
                    "200,400,401,403,404,409,500",
                    "Location"),
            api(
                    "POST",
                    "/api/v1/evidences/{evidenceId}/release",
                    "releaseEvidence",
                    Auth.BEARER,
                    JSON_MEDIA_TYPE,
                    "200",
                    "200,400,401,403,404,409,500",
                    "Location"));

    /**
     * Mappings that exist in the servlet container but are deliberately not operations of the API: the sanitized
     * actuator probes, the springdoc document and Swagger UI resources, and the {@code 405} guard that keeps a
     * {@code GET} on the login path from being translated into a {@code 500} by the catch-all advice.
     */
    private static final Set<String> NON_API_MAPPINGS = Set.of(
            "ANY /error",
            "GET /api/v1/auth/login",
            "GET /actuator/health",
            "GET /actuator/health/**",
            "GET /v3/api-docs",
            "GET /v3/api-docs.yaml",
            "GET /v3/api-docs/swagger-config",
            "GET /swagger-ui.html");

    /** Actuator may publish exactly the sanitized health probes and nothing else. */
    private static final Set<String> APPROVED_ACTUATOR_MAPPINGS =
            Set.of("GET /actuator/health", "GET /actuator/health/**");

    /** Path fragments that would betray a generic, aliased, bulk or demo-only route anywhere in the surface. */
    private static final List<String> FORBIDDEN_PATH_FRAGMENTS = List.of(
            "/command",
            "/commands",
            "/event-append",
            "/append",
            "/bulk",
            "/batch",
            "/import",
            "/export",
            "/demo",
            "/seed",
            "/reset",
            "/rpc",
            "/graphql",
            "/console",
            "/debug",
            "/internal",
            "/unseal",
            "/reopen",
            "/restore",
            "/holder",
            "/storage",
            "/chain-head",
            "/sql",
            "/query");

    /** Names no published schema may ever carry: persistence internals, optimistic version, storage and chain anchors. */
    private static final List<String> FORBIDDEN_SCHEMA_PROPERTIES = List.of(
            "version",
            "storageKey",
            "storagePath",
            "storageRoot",
            "absolutePath",
            "filePath",
            "custodyEventCount",
            "custodyChainHeadHash",
            "chainHeadHash",
            "headHash",
            "eventCount",
            "passwordHash",
            "password_hash",
            "payloadJson",
            "normalizedUsername",
            "normalizedEmail",
            "normalizedReferenceTag",
            "custodyCase",
            "evidence_id",
            "hibernateLazyInitializer",
            "handler");

    /** Persistence types that must never leak into the published component schemas. */
    private static final List<String> FORBIDDEN_SCHEMA_NAMES =
            List.of("CustodyCase", "DigitalEvidence", "Operator", "CaseMembership", "CustodyEvent", "CaseMembershipId");

    private static final Pattern JWT_SHAPE = Pattern.compile("eyJ[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}\\.");
    private static final Pattern BCRYPT_SHAPE = Pattern.compile("\\$2[aby]\\$\\d\\d\\$");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext applicationContext;

    private JsonNode document;

    @BeforeEach
    void loadDocument() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        document = JSON.readTree(body);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 1. The allowlist itself, taken from the live Spring request mappings.
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void theLiveSpringRequestMappingsAreExactlyTheApprovedSurfacePlusTheDeclaredInfrastructure() {
        Set<String> approved = new TreeSet<>(NON_API_MAPPINGS);
        API.forEach(endpoint -> approved.add(endpoint.key()));

        assertThat(mappedRoutes())
                .as("the dispatched request mappings are the contract; an approved endpoint may not disappear and an "
                        + "unexpected one may not appear")
                .containsExactlyInAnyOrderElementsOf(approved);
    }

    @Test
    void everyTestOnlyHandlerStaysInsideItsOwnNamespaceAndNeverShips() {
        assertThat(testFixtureRoutes())
                .as("a fixture controller must never be able to masquerade as an API route")
                .allSatisfy(route -> assertThat(route).contains(" /api/test/"));
        assertThat(mappedRoutes())
                .as("no shipped route may live in the fixture namespace")
                .noneMatch(route -> route.contains(" /api/test/"));
    }

    @Test
    void noMappedRouteCarriesAGenericAliasBulkOrDemoShape() {
        List<String> offenders = new ArrayList<>();
        for (String route : mappedRoutes()) {
            String path = route.substring(route.indexOf(' ') + 1).toLowerCase(Locale.ROOT);
            FORBIDDEN_PATH_FRAGMENTS.stream()
                    .filter(path::contains)
                    .forEach(fragment -> offenders.add(route + " contains " + fragment));
        }
        assertThat(offenders)
                .as("no generic command, event-append, bulk, demo or introspection route may be mapped")
                .isEmpty();
    }

    @Test
    void theCustodyEventTimelineIsMappedReadOnlyAndNoEvidenceItemResourceIsMutable() {
        List<String> writeRoutes = mappedRoutes().stream()
                .filter(route -> route.contains("/events"))
                .filter(route -> !route.startsWith("GET "))
                .toList();
        assertThat(writeRoutes)
                .as("custody events are append-only through domain workflows; no event write route may exist")
                .isEmpty();

        assertThat(mappedRoutes().stream()
                        .filter(route -> route.endsWith(" /api/v1/evidences/{evidenceId}"))
                        .toList())
                .as("the evidence item resource is read-only")
                .containsExactly("GET /api/v1/evidences/{evidenceId}");
    }

    @Test
    void actuatorPublishesOnlyTheSanitizedHealthProbesAndNeverAppearsInTheContract() throws Exception {
        Set<String> actuatorRoutes = mappedRoutes().stream()
                .filter(route -> route.contains("/actuator"))
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        assertThat(actuatorRoutes)
                .as("actuator may publish nothing beyond the sanitized health probes")
                .containsExactlyInAnyOrderElementsOf(APPROVED_ACTUATOR_MAPPINGS);

        for (String probe : SecurityConfig.PUBLIC_HEALTH_PROBES) {
            mockMvc.perform(get(probe))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status")
                            .value("UP"))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.components")
                                    .doesNotExist())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.details")
                            .doesNotExist());
        }
        for (String forbidden : List.of(
                "/actuator",
                "/actuator/env",
                "/actuator/beans",
                "/actuator/metrics",
                "/actuator/mappings",
                "/actuator/heapdump",
                "/actuator/loggers",
                "/actuator/info",
                "/actuator/shutdown",
                "/actuator/health/db",
                "/actuator/health/evidenceStorage")) {
            mockMvc.perform(get(forbidden)).andExpect(status().isUnauthorized());
        }

        assertThat(document.toString())
                .as("no actuator path may be advertised in the published contract")
                .doesNotContain("/actuator");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 2. The generated document reconciled against the same allowlist.
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void theDocumentPublishesExactlyTheApprovedOperationsWithTheirApprovedIdentifiers() {
        JsonNode paths = document.get("paths");
        Set<String> documented = new TreeSet<>();
        Map<String, String> operationIds = new LinkedHashMap<>();
        // springdoc scans the whole context, so the test-only exception fixture is documented here and never in the
        // packaged application. Its routes are asserted separately to live only under the fixture namespace.
        Set<String> fixturePaths = testFixtureRoutes().stream()
                .map(route -> route.substring(route.indexOf(' ') + 1))
                .collect(java.util.stream.Collectors.toSet());
        for (String path : paths.propertyNames()) {
            if (fixturePaths.contains(path)) {
                continue;
            }
            for (String method : paths.get(path).propertyNames()) {
                String key = method.toUpperCase(Locale.ROOT) + " " + path;
                documented.add(key);
                operationIds.put(
                        key, paths.get(path).get(method).get("operationId").stringValue());
            }
        }

        assertThat(documented)
                .as("the document must publish the approved surface and nothing else")
                .containsExactlyInAnyOrderElementsOf(
                        API.stream().map(Endpoint::key).collect(java.util.stream.Collectors.toSet()));
        assertThat(operationIds)
                .as("operation identifiers are part of the contract and must be stable and explicit")
                .containsExactlyInAnyOrderEntriesOf(
                        API.stream().collect(java.util.stream.Collectors.toMap(Endpoint::key, Endpoint::operationId)));
        assertThat(new LinkedHashSet<>(operationIds.values()))
                .as("no two operations may share an identifier")
                .hasSize(API.size());
    }

    @Test
    void everyOperationDeclaresTheApprovedRequestMediaTypeStatusesAndSuccessHeaders() {
        for (Endpoint endpoint : API) {
            JsonNode operation = operation(endpoint);

            JsonNode requestBody = operation.get("requestBody");
            if (endpoint.requestMediaType() == null) {
                assertThat(requestBody)
                        .as("%s must take no request body", endpoint.key())
                        .isNull();
            } else {
                assertThat(requestBody)
                        .as("%s must document a request body", endpoint.key())
                        .isNotNull();
                assertThat(requestBody.get("content").propertyNames())
                        .as("%s request media type", endpoint.key())
                        .containsExactly(endpoint.requestMediaType());
            }

            JsonNode responses = operation.get("responses");
            assertThat(responses.propertyNames())
                    .as("%s response statuses", endpoint.key())
                    .containsExactlyInAnyOrderElementsOf(endpoint.statuses());

            JsonNode success = responses.get(endpoint.successStatus());
            Set<String> headers = success.get("headers") == null
                    ? Set.of()
                    : new LinkedHashSet<>(success.get("headers").propertyNames());
            assertThat(headers)
                    .as("%s success headers", endpoint.key())
                    .containsExactlyInAnyOrderElementsOf(endpoint.successHeaders());
            for (String header : endpoint.successHeaders()) {
                assertThat(success.get("headers").get(header).get("required").booleanValue())
                        .as("%s header %s must be required", endpoint.key(), header)
                        .isTrue();
            }
        }
    }

    @Test
    void theBearerSchemeIsDeclaredGloballyAndEveryOperationDeclaresItsOwnRequirement() {
        JsonNode scheme = document.get("components").get("securitySchemes").get("bearerAuth");
        assertThat(scheme).as("the bearer scheme must be published").isNotNull();
        assertThat(scheme.get("type").stringValue()).isEqualTo("http");
        assertThat(scheme.get("scheme").stringValue()).isEqualTo("bearer");
        assertThat(scheme.get("bearerFormat").stringValue()).isEqualTo("JWT");
        assertThat(document.get("components").get("securitySchemes").propertyNames())
                .as("exactly one authentication mechanism exists")
                .containsExactly("bearerAuth");

        JsonNode global = document.get("security");
        assertThat(global.size()).isEqualTo(1);
        assertThat(global.get(0).get("bearerAuth")).isNotNull();

        assertThat(document.get("info").get("version").stringValue()).isEqualTo(OpenApiConfig.API_VERSION);

        for (Endpoint endpoint : API) {
            JsonNode security = operation(endpoint).get("security");
            if (endpoint.auth() == Auth.PUBLIC) {
                assertThat(security)
                        .as("%s must override the global requirement with an empty one", endpoint.key())
                        .isNotNull();
                assertThat(security.isEmpty())
                        .as("%s must be reachable unauthenticated", endpoint.key())
                        .isTrue();
            } else {
                assertThat(security)
                        .as("%s must declare the bearer requirement at operation level", endpoint.key())
                        .isNotNull();
                assertThat(security.size()).isEqualTo(1);
                assertThat(security.get(0).get("bearerAuth"))
                        .as("%s must require bearerAuth", endpoint.key())
                        .isNotNull();
            }
        }
    }

    @Test
    void onlyTheLoginOperationIsUnauthenticatedAndTheSecurityFilterChainAgrees() throws Exception {
        List<String> publicOperations = API.stream()
                .filter(endpoint -> endpoint.auth() == Auth.PUBLIC)
                .map(Endpoint::key)
                .toList();
        assertThat(publicOperations)
                .as("authentication is the default; exactly one operation opts out")
                .containsExactly("POST /api/v1/auth/login");

        for (Endpoint endpoint : API) {
            if (endpoint.auth() == Auth.PUBLIC || !"GET".equals(endpoint.method())) {
                continue;
            }
            String concrete = endpoint.path()
                    .replace("{caseId}", "11111111-1111-4111-8111-111111111111")
                    .replace("{evidenceId}", "22222222-2222-4222-8222-222222222222")
                    .replace("{eventId}", "33333333-3333-4333-8333-333333333333")
                    .replace("{operatorId}", "44444444-4444-4444-8444-444444444444")
                    .replace("{id}", "55555555-5555-4555-8555-555555555555");
            mockMvc.perform(get(concrete))
                    .andExpect(status().isUnauthorized())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                            .contentTypeCompatibleWith(PROBLEM_JSON));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 3. Problem Details reconciliation with the runtime catalogue.
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void everyNonSuccessResponseIsDocumentedAsAProblemDetail() {
        for (Endpoint endpoint : API) {
            JsonNode responses = operation(endpoint).get("responses");
            for (String status : responses.propertyNames()) {
                if (status.startsWith("2")) {
                    continue;
                }
                JsonNode content = responses.get(status).get("content");
                assertThat(content)
                        .as("%s status %s must carry a body", endpoint.key(), status)
                        .isNotNull();
                assertThat(content.propertyNames())
                        .as("%s status %s must be a Problem Detail", endpoint.key(), status)
                        .containsExactly(PROBLEM_JSON);
                assertThat(content.get(PROBLEM_JSON).get("schema").toString())
                        .as("%s status %s must reference the ProblemDetail schema", endpoint.key(), status)
                        .contains("ProblemDetail");
            }
        }
    }

    @Test
    void everyProblemTypeDocumentedInAnExampleBelongsToTheRuntimeCatalogue() {
        Set<String> catalogue = runtimeProblemCatalogue();
        assertThat(catalogue).as("the runtime catalogue must be non-trivial").hasSizeGreaterThanOrEqualTo(25);
        catalogue.forEach(type -> assertThat(type)
                .as("every problem type must be stable and namespaced")
                .startsWith(PROBLEM_TYPE_PREFIX));

        Set<String> documented = new TreeSet<>();
        collectProblemTypes(document, documented);
        assertThat(documented)
                .as("the document must actually show problem examples")
                .isNotEmpty();
        assertThat(documented)
                .as("no documented problem type may be absent from the runtime catalogue")
                .isSubsetOf(catalogue);
    }

    @Test
    void theRuntimeEmitsProblemDetailsWithTheDocumentedMediaTypeStatusAndCatalogueType() throws Exception {
        Set<String> catalogue = runtimeProblemCatalogue();

        record Probe(String description, org.springframework.test.web.servlet.RequestBuilder request, int status) {}
        List<Probe> probes = List.of(
                new Probe("unauthenticated read", get("/api/v1/cases"), 401),
                new Probe(
                        "invalid bearer token",
                        get("/api/v1/cases").header("Authorization", "Bearer not-a-token"),
                        401),
                new Probe("unknown resource path", get("/api/v1/does-not-exist"), 401));

        for (Probe probe : probes) {
            String body = mockMvc.perform(probe.request())
                    .andExpect(status().is(probe.status()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                            .contentTypeCompatibleWith(PROBLEM_JSON))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            JsonNode problem = JSON.readTree(body);
            assertThat(problem.get("type").stringValue())
                    .as("%s must use a catalogued problem type", probe.description())
                    .isIn(catalogue);
            assertThat(problem.get("status").intValue()).isEqualTo(probe.status());
            assertThat(problem.propertyNames())
                    .as("%s must not carry extra diagnostic properties", probe.description())
                    .doesNotContain("stackTrace", "trace", "exception", "cause");
        }

        // The method guard exists so a GET on the login path answers 405 instead of the catch-all 500.
        mockMvc.perform(get("/api/v1/auth/login")).andExpect(status().isMethodNotAllowed());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 4. Schemas: no persistence internals, no storage information, synthetic examples only.
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void noPublishedSchemaExposesPersistenceInternalsStorageInformationOrTheOptimisticVersion() {
        JsonNode schemas = document.get("components").get("schemas");
        assertThat(schemas.propertyNames())
                .as("no persistence entity may be published as a schema")
                .doesNotContainAnyElementsOf(FORBIDDEN_SCHEMA_NAMES);

        List<String> offenders = new ArrayList<>();
        for (String name : schemas.propertyNames()) {
            JsonNode properties = schemas.get(name).get("properties");
            if (properties == null) {
                continue;
            }
            for (String property : properties.propertyNames()) {
                if (FORBIDDEN_SCHEMA_PROPERTIES.contains(property)) {
                    offenders.add(name + "." + property);
                }
            }
        }
        assertThat(offenders)
                .as("published schemas must expose no internal field")
                .isEmpty();

        // The two schemas that carry evidence content facts must expose the digests and never the location.
        assertThat(schemas.get("EvidenceResponse").get("properties").propertyNames())
                .contains("contentSha256", "contextualSha256", "fileSize", "originalFilename");
        assertThat(document.toString())
                .as("no storage layout may be described anywhere in the contract")
                .doesNotContain("/var/lib/proofchain")
                .doesNotContain("storageKey")
                .doesNotContain(".staging");
    }

    @Test
    void everyDocumentedExampleIsSyntheticAndCarriesNoCredentialOrRealHost() {
        List<String> examples = new ArrayList<>();
        collectExamples(document, examples);
        assertThat(examples).as("the contract must actually document examples").hasSizeGreaterThanOrEqualTo(50);

        for (String example : examples) {
            assertThat(JWT_SHAPE.matcher(example).find())
                    .as("example %s must not contain a token-shaped value", example)
                    .isFalse();
            assertThat(BCRYPT_SHAPE.matcher(example).find())
                    .as("example %s must not contain a password hash", example)
                    .isFalse();
            assertThat(example.toLowerCase(Locale.ROOT))
                    .as("example %s must not contain a filesystem location or a private host", example)
                    .doesNotContain("/var/lib")
                    .doesNotContain("/home/")
                    .doesNotContain("c:\\")
                    .doesNotContain("localhost:")
                    .doesNotContain("127.0.0.1");
        }

        // Credentials are documented as redaction markers, never as usable values.
        assertThat(document.get("components")
                        .get("schemas")
                        .get("LoginRequest")
                        .get("properties")
                        .get("password")
                        .get("example")
                        .stringValue())
                .isEqualTo("<redacted>");
        assertThat(document.get("components")
                        .get("schemas")
                        .get("LoginResponse")
                        .get("properties")
                        .get("accessToken")
                        .get("example")
                        .stringValue())
                .isEqualTo("<redacted-access-token>");

        // Every documented UUID example must be a syntactically valid, obviously fictional identifier.
        for (String example : examples) {
            if (example.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
                assertThat(java.util.UUID.fromString(example)).isNotNull();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 5. The Postman package is bound to the same allowlist.
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void thePostmanCollectionIsWellFormedAndOnlyCallsApprovedEndpoints() throws IOException {
        JsonNode collection = readJson(Path.of("postman", "ProofChain.postman_collection.json"));

        assertThat(collection.get("info").get("schema").stringValue())
                .as("the collection must declare the Postman v2.1 schema")
                .isEqualTo("https://schema.getpostman.com/json/collection/v2.1.0/collection.json");
        assertThat(collection.get("info").get("name").stringValue()).isEqualTo("ProofChain API");
        assertThat(collection.get("item").isArray()).isTrue();

        List<JsonNode> requests = new ArrayList<>();
        collectRequests(collection.get("item"), requests);
        assertThat(requests)
                .as("the collection must cover the delivered surface")
                .hasSizeGreaterThanOrEqualTo(40);

        Set<String> approvedTemplates = new TreeSet<>();
        API.forEach(endpoint -> approvedTemplates.add(endpoint.key()));

        List<String> unapproved = new ArrayList<>();
        Set<String> exercised = new TreeSet<>();
        for (JsonNode request : requests) {
            String method = request.get("request").get("method").stringValue();
            String raw = request.get("request").get("url").get("raw").stringValue();
            String normalized = normalizePostmanPath(raw);
            if (normalized == null) {
                continue; // health, documentation and deliberately unmapped negative probes
            }
            String key = method + " " + normalized;
            exercised.add(key);
            if (!approvedTemplates.contains(key)) {
                unapproved.add(request.get("name").stringValue() + " -> " + key);
            }
        }
        assertThat(unapproved)
                .as("the collection may only exercise approved endpoints")
                .isEmpty();
        assertThat(exercised)
                .as("the collection must exercise every approved endpoint at least once")
                .containsAll(approvedTemplates);
    }

    @Test
    void thePostmanEnvironmentContainsPlaceholdersOnlyAndNoTrackedArtifactCarriesASecret() throws IOException {
        Path environmentPath = Path.of("postman", "ProofChain.local.postman_environment.json");
        JsonNode environment = readJson(environmentPath);
        assertThat(environment.get("name").stringValue()).isEqualTo("ProofChain local");
        assertThat(environment.get("values").isArray()).isTrue();

        List<String> offenders = new ArrayList<>();
        for (JsonNode value : environment.get("values")) {
            String key = value.get("key").stringValue();
            String content = value.get("value").stringValue();
            if (JWT_SHAPE.matcher(content).find()
                    || BCRYPT_SHAPE.matcher(content).find()) {
                offenders.add(key + " carries a token or hash");
            }
            if (content.startsWith("/") && content.length() > 1 || content.matches("^[A-Za-z]:\\\\.*")) {
                offenders.add(key + " carries an absolute path");
            }
            if (key.toLowerCase(Locale.ROOT).contains("password")
                    && !content.isEmpty()
                    && !content.contains("placeholder")) {
                offenders.add(key + " must stay a named placeholder");
            }
        }
        assertThat(offenders)
                .as("the tracked environment must hold placeholders and non-sensitive defaults only")
                .isEmpty();

        Path collectionPath = Path.of("postman", "ProofChain.postman_collection.json");
        Path guidePath = Path.of("postman", "README.md");
        for (Path artifact : List.of(environmentPath, collectionPath, guidePath)) {
            String content = Files.readString(artifact, StandardCharsets.UTF_8);
            assertThat(JWT_SHAPE.matcher(content).find())
                    .as("%s must contain no token", artifact)
                    .isFalse();
            assertThat(BCRYPT_SHAPE.matcher(content).find())
                    .as("%s must contain no password hash", artifact)
                    .isFalse();
            assertThat(content)
                    .as("%s must contain no connection string or host filesystem path", artifact)
                    .doesNotContain("jdbc:")
                    .doesNotContain("/home/")
                    .doesNotContain("/Users/")
                    .doesNotContain("C:\\");
        }
        // The two executable artifacts additionally carry no credential name and no storage layout at all. The guide
        // is exempt because it must name the environment variables an operator has to set in the untracked .env.
        for (Path artifact : List.of(environmentPath, collectionPath)) {
            assertThat(Files.readString(artifact, StandardCharsets.UTF_8))
                    .as("%s must carry no credential material or storage layout", artifact)
                    .doesNotContain("POSTGRES_PASSWORD")
                    .doesNotContain("DB_PASSWORD")
                    .doesNotContain("/var/lib/proofchain");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------------

    private JsonNode operation(Endpoint endpoint) {
        JsonNode path = document.get("paths").get(endpoint.path());
        assertThat(path).as("path %s must be published", endpoint.path()).isNotNull();
        JsonNode operation = path.get(endpoint.method().toLowerCase(Locale.ROOT));
        assertThat(operation)
                .as("operation %s must be published", endpoint.key())
                .isNotNull();
        return operation;
    }

    /** Every route the dispatcher can reach, as {@code METHOD path}, across all request-mapping handler mappings. */
    private Set<String> mappedRoutes() {
        return routes(false);
    }

    /**
     * A {@code @SpringBootTest} context also component-scans the test sources, so the exception fixture controller
     * would otherwise pollute the surface. Handlers are partitioned by the code source their declaring class was loaded
     * from: {@code target/classes} is the shipped application, {@code target/test-classes} never ships.
     */
    private Set<String> testFixtureRoutes() {
        return routes(true);
    }

    private Set<String> routes(boolean fixtures) {
        Set<String> routes = new TreeSet<>();
        for (RequestMappingInfoHandlerMapping mapping : applicationContext
                .getBeansOfType(RequestMappingInfoHandlerMapping.class)
                .values()) {
            for (Map.Entry<RequestMappingInfo, HandlerMethod> entry :
                    mapping.getHandlerMethods().entrySet()) {
                if (loadedFromTestSources(entry.getValue().getBeanType()) != fixtures) {
                    continue;
                }
                RequestMappingInfo info = entry.getKey();
                Set<String> patterns = info.getPathPatternsCondition() == null
                        ? Set.of()
                        : info.getPathPatternsCondition().getPatternValues();
                Set<org.springframework.web.bind.annotation.RequestMethod> methods =
                        info.getMethodsCondition().getMethods();
                for (String pattern : patterns) {
                    if (methods.isEmpty()) {
                        routes.add("ANY " + pattern);
                    } else {
                        methods.forEach(method -> routes.add(method.name() + " " + pattern));
                    }
                }
            }
        }
        return routes;
    }

    private static boolean loadedFromTestSources(Class<?> type) {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        return source != null
                && source.getLocation() != null
                && source.getLocation().toString().contains("test-classes");
    }

    /** The stable problem catalogue, read reflectively from the single runtime source of truth. */
    private static Set<String> runtimeProblemCatalogue() {
        Set<String> catalogue = new TreeSet<>();
        for (Field field : ProblemTypes.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == URI.class) {
                try {
                    catalogue.add(((URI) field.get(null)).toString());
                } catch (IllegalAccessException exception) {
                    throw new AssertionError("the problem catalogue must be readable", exception);
                }
            }
        }
        return catalogue;
    }

    private static void collectProblemTypes(JsonNode node, Set<String> collected) {
        if (node.isObject()) {
            for (String name : node.propertyNames()) {
                JsonNode child = node.get(name);
                if ("type".equals(name)
                        && child.isString()
                        && child.stringValue().startsWith(PROBLEM_TYPE_PREFIX)) {
                    collected.add(child.stringValue());
                } else {
                    collectProblemTypes(child, collected);
                }
            }
        } else if (node.isArray()) {
            node.forEach(child -> collectProblemTypes(child, collected));
        }
    }

    private static void collectExamples(JsonNode node, List<String> collected) {
        if (node.isObject()) {
            for (String name : node.propertyNames()) {
                JsonNode child = node.get(name);
                if ("example".equals(name) || "examples".equals(name)) {
                    collectScalars(child, collected);
                } else {
                    collectExamples(child, collected);
                }
            }
        } else if (node.isArray()) {
            node.forEach(child -> collectExamples(child, collected));
        }
    }

    private static void collectScalars(JsonNode node, List<String> collected) {
        if (node.isObject()) {
            node.propertyNames().forEach(name -> collectScalars(node.get(name), collected));
        } else if (node.isArray()) {
            node.forEach(child -> collectScalars(child, collected));
        } else if (node.isString()) {
            collected.add(node.stringValue());
        }
    }

    private static void collectRequests(JsonNode items, List<JsonNode> collected) {
        for (JsonNode item : items) {
            if (item.has("item")) {
                collectRequests(item.get("item"), collected);
            } else if (item.has("request")) {
                collected.add(item);
            }
        }
    }

    private static JsonNode readJson(Path path) throws IOException {
        assertThat(Files.isRegularFile(path)).as("%s must be delivered", path).isTrue();
        return JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
    }

    /**
     * Maps a concrete Postman URL back onto its OpenAPI path template. Health, documentation and the deliberately
     * unmapped negative probes return {@code null} so they are ignored rather than rejected.
     */
    private static String normalizePostmanPath(String rawUrl) {
        String path = rawUrl.replace("{{baseUrl}}", "");
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        if (!path.startsWith("/api/v1/")) {
            return null;
        }
        String[] segments = path.split("/", -1);
        List<String> rebuilt = new ArrayList<>();
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            if (segment.isEmpty()) {
                continue;
            }
            String previous = rebuilt.isEmpty() ? "" : rebuilt.get(rebuilt.size() - 1);
            if (isIdentifierSegment(segment)) {
                rebuilt.add(
                        switch (previous) {
                            case "cases" -> "{caseId}";
                            case "evidences" -> "{evidenceId}";
                            case "events" -> "{eventId}";
                            case "members" -> "{operatorId}";
                            case "operators" -> "{id}";
                            default -> "{unknown}";
                        });
            } else {
                rebuilt.add(segment);
            }
        }
        return "/" + String.join("/", rebuilt);
    }

    private static boolean isIdentifierSegment(String segment) {
        if (segment.startsWith("{{") && segment.endsWith("}}")) {
            return true;
        }
        return segment.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    /** Kept for readability of failure messages when the mapping set changes. */
    @SuppressWarnings("unused")
    private static String render(Set<String> routes) {
        return routes.stream().sorted(Comparator.naturalOrder()).reduce("", (left, right) -> left + "\n" + right);
    }

    /** Guards against an accidentally empty allowlist after a refactor. */
    @Test
    void theAllowlistItselfIsComplete() {
        assertThat(API).hasSize(27);
        assertThat(API.stream().map(Endpoint::key).distinct().count()).isEqualTo(API.size());
        assertThat(Arrays.stream(SecurityConfig.PUBLIC_HEALTH_PROBES).toList())
                .containsExactly("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness");
    }
}
