package it.itsprodigi.proofchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import it.itsprodigi.proofchain.auth.application.JwtTokenService;
import it.itsprodigi.proofchain.auth.logging.AuthEventLogger;
import it.itsprodigi.proofchain.custodycase.domain.CaseMembership;
import it.itsprodigi.proofchain.custodycase.domain.CasePriority;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.custodycase.persistence.CustodyCaseRepository;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceRepository;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Release security audit of the two observable output channels: the application's own log stream and the HTTP responses
 * it returns.
 *
 * <p>The suite drives a complete custody lifecycle with deliberately recognisable secret material — a plaintext
 * password, a bearer token, reason text, metadata prose, file bytes, content and contextual hashes, the storage key and
 * the absolute storage root — and then fails if any of it appears in either channel. It is a leak detector, not a
 * behaviour test: every assertion is of the form "this string must never be observable".
 *
 * <p>Scope of the log channel: the loggers the application itself owns, at the level the production configuration runs
 * them at — {@code it.itsprodigi.proofchain} and the dedicated {@code AUTH_AUDIT} audit logger. Third-party loggers
 * (Hibernate, Spring, Tomcat, HikariCP) are outside the application's logging surface and are recorded as a documented
 * observation in the release evidence rather than silently asserted here.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityLogAndResponseLeakAuditIT extends PostgreSqlIntegrationTest {

    private static final String APPLICATION_LOGGER = "it.itsprodigi.proofchain";

    /** Deliberately recognisable secret material. None of it may ever reach a log line or a response body. */
    private static final String PASSWORD = "l3ak-canary-Passw0rd!";

    private static final String SECRET_TITLE = "LEAKCANARY-TITLE";
    private static final String SECRET_DESCRIPTION = "LEAKCANARY-DESCRIPTION";
    private static final String SECRET_NOTES = "LEAKCANARY-ACQUISITION-NOTES";
    private static final String SECRET_TRANSFER_REASON = "LEAKCANARY-TRANSFER-REASON";
    private static final String SECRET_METADATA_REASON = "LEAKCANARY-METADATA-TITLE";
    private static final String SECRET_SEAL_REASON = "LEAKCANARY-SEAL-REASON";
    private static final String SECRET_RELEASE_REASON = "LEAKCANARY-RELEASE-REASON";
    private static final byte[] SECRET_FILE_BYTES = "LEAKCANARY-FILE-BYTES-🔐".getBytes(StandardCharsets.UTF_8);

    /** Fragments that would prove an internal detail escaped, independently of the canary values above. */
    private static final List<String> INTERNAL_DETAIL_FRAGMENTS = List.of(
            "it.itsprodigi.proofchain",
            "org.springframework",
            "org.hibernate",
            "org.postgresql",
            "jakarta.persistence",
            "com.zaxxer.hikari",
            "\tat ",
            "\n\tat",
            "select ",
            "insert into",
            "update set",
            "delete from",
            "constraint",
            "sqlstate",
            "psqlexception",
            "optimisticlocking",
            "pessimistic",
            "lock_timeout",
            "could not execute statement",
            "content.bin",
            "/tmp/",
            "/var/lib/proofchain");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void configureEvidenceStorage(DynamicPropertyRegistry registry) {
        registry.add("proofchain.storage.root", () -> storageRoot.toString());
    }

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
    private JdbcTemplate jdbcTemplate;

    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private final List<Logger> instrumented = new ArrayList<>();

    private Operator manager;
    private Operator officer;
    private Operator otherOfficer;
    private Operator auditor;
    private Operator outsider;

    @BeforeEach
    void setUp() throws IOException {
        cleanDatabase();
        cleanStorage();
        manager = saveOperator("manager", OperatorRole.CASE_MANAGER);
        officer = saveOperator("officer", OperatorRole.EVIDENCE_OFFICER);
        otherOfficer = saveOperator("other-officer", OperatorRole.EVIDENCE_OFFICER);
        auditor = saveOperator("auditor", OperatorRole.AUDITOR);
        outsider = saveOperator("outsider", OperatorRole.CASE_MANAGER);
        startCapturing();
    }

    @AfterEach
    void tearDown() throws IOException {
        stopCapturing();
        cleanDatabase();
        cleanStorage();
    }

    /**
     * Drives the whole custody lifecycle and then proves the application log contains none of the canary material, no
     * full hash, no storage key, no absolute path and no attached stack trace.
     */
    @Test
    void applicationLogsNeverLeakCredentialsTokensReasonsMetadataPayloadsHashesStorageKeysOrPaths() throws Exception {
        CustodyCase custodyCase = caseWithMembers(manager, officer, otherOfficer, auditor);

        String token = login(manager);
        loginRejected(manager);

        UUID evidenceId = registerEvidence(custodyCase, token);
        String contentSha256 = column(evidenceId, "content_sha256");
        String contextualSha256 = column(evidenceId, "contextual_sha256");
        String storageKey = column(evidenceId, "storage_key");
        String payloadJson = jdbcTemplate.queryForObject(
                "SELECT payload_json FROM custody_events WHERE evidence_id = ? ORDER BY sequence_number LIMIT 1",
                String.class,
                evidenceId);

        perform(post("/api/v1/evidences/{id}/transfer", evidenceId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(java.util.Map.of(
                                "newHolderId", otherOfficer.getId().toString(), "reason", SECRET_TRANSFER_REASON))))
                .andExpect(status().isOk());
        perform(patch("/api/v1/evidences/{id}/metadata", evidenceId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                java.util.Map.of("title", SECRET_METADATA_REASON, "reason", SECRET_METADATA_REASON))))
                .andExpect(status().isOk());
        perform(post("/api/v1/evidences/{id}/verify-integrity", evidenceId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        perform(post("/api/v1/evidences/{id}/seal", evidenceId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(java.util.Map.of("reason", SECRET_SEAL_REASON))))
                .andExpect(status().isOk());
        perform(post("/api/v1/evidences/{id}/release", evidenceId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(java.util.Map.of("reason", SECRET_RELEASE_REASON))))
                .andExpect(status().isOk());
        perform(post("/api/v1/evidences/{id}/verify-chain", evidenceId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        perform(get("/api/v1/evidences/{id}/events", evidenceId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        perform(get("/api/v1/evidences/{id}/download", evidenceId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        // Denials and anti-enumeration also produce log lines and must be just as quiet.
        perform(get("/api/v1/evidences/{id}", evidenceId).header(HttpHeaders.AUTHORIZATION, bearer(outsider)))
                .andExpect(status().isNotFound());
        perform(post("/api/v1/evidences/{id}/seal", evidenceId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(auditor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(java.util.Map.of("reason", SECRET_SEAL_REASON))))
                .andExpect(status().isForbidden());
        perform(get("/api/v1/evidences/{id}", UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
        perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());

        String log = capturedLog();
        // Non-vacuity: the capture must demonstrably contain the real command and audit lines, otherwise every
        // "does not contain" assertion below would pass against an empty string.
        assertThat(log)
                .as("the audit must actually have observed the application and audit log")
                .contains("Evidence registration result=success")
                .contains("Custody transfer result=success")
                .contains("Operational custody command result=success")
                .contains("Evidence seal result=success")
                .contains("Evidence release result=success")
                .contains("Custody chain verification result=valid")
                .contains("event=LOGIN_SUCCESS")
                .contains("event=LOGIN_FAILURE")
                .contains("event=ACCESS_DENIED")
                .contains("event=INVALID_TOKEN")
                .contains(evidenceId.toString());

        assertThat(log)
                .as("plaintext credentials, bearer tokens and password hashes must never be logged")
                .doesNotContain(PASSWORD)
                .doesNotContain(token)
                .doesNotContain("Bearer ")
                .doesNotContain("$2a$")
                .doesNotContain("password");
        assertThat(log)
                .as("operator-supplied reason text and metadata prose must never be logged")
                .doesNotContain(SECRET_TITLE)
                .doesNotContain(SECRET_DESCRIPTION)
                .doesNotContain(SECRET_NOTES)
                .doesNotContain(SECRET_TRANSFER_REASON)
                .doesNotContain(SECRET_METADATA_REASON)
                .doesNotContain(SECRET_SEAL_REASON)
                .doesNotContain(SECRET_RELEASE_REASON);
        assertThat(log)
                .as("event payload JSON and the canonical preimage must never be logged")
                .doesNotContain(payloadJson)
                .doesNotContain("\"payloadVersion\"")
                .doesNotContain("\"previousHash\"")
                .doesNotContain("\"contentSha256\"")
                .doesNotContain("\"referenceTag\"");
        assertThat(log)
                .as("file bytes must never be logged")
                .doesNotContain(new String(SECRET_FILE_BYTES, StandardCharsets.UTF_8));
        assertThat(log)
                .as("full content, contextual, event and chain-head hashes must never be logged")
                .doesNotContain(contentSha256)
                .doesNotContain(contextualSha256);
        assertThat(log).as("no 64-character hexadecimal hash may appear").doesNotContainPattern("[0-9a-f]{64}");
        assertThat(log)
                .as("storage keys and absolute filesystem paths must never be logged")
                .doesNotContain(storageKey)
                .doesNotContain(storageRoot.toString())
                .doesNotContain("content.bin")
                .doesNotContain(".staging");
        assertThat(captured.list)
                .as("no application log event may carry a stack trace at its production level")
                .allSatisfy(event -> assertThat(event.getThrowableProxy())
                        .as("log event %s must not attach a throwable", event.getFormattedMessage())
                        .isNull());
    }

    /** Every Problem Detail the API can return must be free of implementation internals. */
    @Test
    void problemDetailsNeverExposeStackTracesSqlPersistenceInternalsLockDetailsOrClassNames() throws Exception {
        CustodyCase custodyCase = caseWithMembers(manager, officer, otherOfficer, auditor);
        String token = login(manager);
        UUID evidenceId = registerEvidence(custodyCase, token);

        List<String> bodies = new ArrayList<>();

        // Duplicate reference tag: a database unique-constraint violation surfaced as a domain conflict.
        bodies.add(body(perform(registration(custodyCase, token, "LEAK-01", SECRET_FILE_BYTES))
                .andExpect(status().isConflict())));
        // Terminal-state, no-op, ineligible-holder and closed-case conflicts.
        bodies.add(body(perform(post("/api/v1/evidences/{id}/transfer", evidenceId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(java.util.Map.of(
                                "newHolderId", officer.getId().toString(), "reason", SECRET_TRANSFER_REASON))))
                .andExpect(status().isConflict())));
        bodies.add(body(perform(post("/api/v1/evidences/{id}/transfer", evidenceId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(java.util.Map.of(
                                "newHolderId", auditor.getId().toString(), "reason", SECRET_TRANSFER_REASON))))
                .andExpect(status().isConflict())));
        // Malformed body, unknown property and type mismatch.
        bodies.add(body(perform(post("/api/v1/evidences/{id}/seal", evidenceId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"ok\",\"unknown\":1}"))
                .andExpect(status().isBadRequest())));
        bodies.add(body(perform(post("/api/v1/evidences/{id}/seal", evidenceId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not json"))
                .andExpect(status().isBadRequest())));
        bodies.add(body(perform(get("/api/v1/evidences/{id}", "not-a-uuid")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())));
        // Authentication, authorization and anti-enumeration.
        bodies.add(body(perform(get("/api/v1/evidences/{id}", evidenceId)).andExpect(status().isUnauthorized())));
        bodies.add(body(perform(get("/api/v1/evidences/{id}", evidenceId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token + "x"))
                .andExpect(status().isUnauthorized())));
        bodies.add(body(perform(post("/api/v1/evidences/{id}/seal", evidenceId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(auditor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(java.util.Map.of("reason", "any"))))
                .andExpect(status().isForbidden())));
        bodies.add(body(
                perform(get("/api/v1/evidences/{id}", evidenceId).header(HttpHeaders.AUTHORIZATION, bearer(outsider)))
                        .andExpect(status().isNotFound())));
        bodies.add(body(perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                java.util.Map.of("username", manager.getUsername(), "password", "wrong-" + PASSWORD))))
                .andExpect(status().isUnauthorized())));

        String storageKey = column(evidenceId, "storage_key");
        for (String problem : bodies) {
            String lowered = problem.toLowerCase(Locale.ROOT);
            for (String fragment : INTERNAL_DETAIL_FRAGMENTS) {
                assertThat(lowered)
                        .as("Problem Detail must not expose %s: %s", fragment, problem)
                        .doesNotContain(fragment.toLowerCase(Locale.ROOT));
            }
            assertThat(problem)
                    .as("Problem Detail must not expose storage keys, absolute paths or credentials: %s", problem)
                    .doesNotContain(storageKey)
                    .doesNotContain(storageRoot.toString())
                    .doesNotContain(PASSWORD);
            assertThat(lowered)
                    .as("Problem Detail must not expose an exception type name: %s", problem)
                    .doesNotContain("exception")
                    .doesNotContain("throwable")
                    .doesNotContain("stacktrace");
        }
    }

    /**
     * Minimal applicable transport policy. TLS terminates outside the application, so the suite proves the application
     * does not fabricate an HSTS guarantee over a plaintext request rather than asserting one.
     */
    @Test
    void responsesCarryMinimalSecurityHeadersStayUncacheableAndKeepCorsDefaultDeny() throws Exception {
        CustodyCase custodyCase = caseWithMembers(manager, officer, otherOfficer, auditor);
        String token = login(manager);
        UUID evidenceId = registerEvidence(custodyCase, token);

        for (MvcResult result : List.of(
                perform(get("/api/v1/evidences/{id}", evidenceId)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                .header(HttpHeaders.ORIGIN, "https://attacker.example.org"))
                        .andExpect(status().isOk())
                        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                        .andExpect(header().string("X-Frame-Options", "DENY"))
                        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                        .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"))
                        .andExpect(header().doesNotExist("Set-Cookie"))
                        .andExpect(header().doesNotExist("Strict-Transport-Security"))
                        .andExpect(header().doesNotExist("Server"))
                        .andReturn(),
                perform(get("/api/v1/evidences/{id}/download", evidenceId)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                        .andExpect(status().isOk())
                        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                        .andExpect(header().doesNotExist("Strict-Transport-Security"))
                        .andReturn(),
                perform(post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(JSON.writeValueAsString(
                                        java.util.Map.of("username", manager.getUsername(), "password", PASSWORD))))
                        .andExpect(status().isOk())
                        .andExpect(header().doesNotExist("Set-Cookie"))
                        .andExpect(header().doesNotExist("Strict-Transport-Security"))
                        .andReturn())) {
            String cacheControl = result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL);
            assertThat(cacheControl)
                    .as("every authenticated or credential-bearing response must be uncacheable")
                    .isNotNull()
                    .contains("no-store");
        }
    }

    private String capturedLog() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : captured.list) {
            text.append(event.getLoggerName())
                    .append(' ')
                    .append(event.getFormattedMessage())
                    .append('\n');
        }
        return text.toString();
    }

    private void startCapturing() {
        captured.start();
        for (String name : List.of(APPLICATION_LOGGER, AuthEventLogger.LOGGER_NAME)) {
            Logger logger = (Logger) LoggerFactory.getLogger(name);
            logger.addAppender(captured);
            instrumented.add(logger);
        }
        // The capture observes the level the application actually runs at; it never widens it.
        assertThat(((Logger) LoggerFactory.getLogger(APPLICATION_LOGGER)).getEffectiveLevel())
                .isEqualTo(Level.INFO);
    }

    private void stopCapturing() {
        instrumented.forEach(logger -> logger.detachAppender(captured));
        instrumented.clear();
        captured.stop();
        captured.list.clear();
    }

    private String login(Operator operator) throws Exception {
        String response = body(perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                java.util.Map.of("username", operator.getUsername(), "password", PASSWORD))))
                .andExpect(status().isOk()));
        return JSON.readTree(response).get("accessToken").stringValue();
    }

    private void loginRejected(Operator operator) throws Exception {
        perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                java.util.Map.of("username", operator.getUsername(), "password", "wrong-" + PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    private UUID registerEvidence(CustodyCase custodyCase, String token) throws Exception {
        String response = body(perform(registration(custodyCase, token, "LEAK-01", SECRET_FILE_BYTES))
                .andExpect(status().isCreated()));
        return UUID.fromString(JSON.readTree(response).get("id").stringValue());
    }

    private MockMultipartHttpServletRequestBuilder registration(
            CustodyCase custodyCase, String token, String referenceTag, byte[] content) {
        MockPart metadata = new MockPart("metadata", metadataJson(referenceTag).getBytes(StandardCharsets.UTF_8));
        metadata.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        MockMultipartFile file =
                new MockMultipartFile("file", "canary.bin", MediaType.APPLICATION_OCTET_STREAM_VALUE, content);
        MockPart filePart = new MockPart("file", "canary.bin", content);
        filePart.getHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE);
        return (MockMultipartHttpServletRequestBuilder)
                multipart("/api/v1/cases/{caseId}/evidences", custodyCase.getId())
                        .part(metadata)
                        .part(filePart)
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private String metadataJson(String referenceTag) {
        return """
                {
                  "referenceTag": "%s",
                  "title": "%s",
                  "description": "%s",
                  "sourceType": "DEVICE",
                  "acquisitionMethod": "PHYSICAL",
                  "acquisitionNotes": "%s",
                  "initialHolderId": "%s"
                }
                """.formatted(referenceTag, SECRET_TITLE, SECRET_DESCRIPTION, SECRET_NOTES, officer.getId());
    }

    private org.springframework.test.web.servlet.ResultActions perform(
            org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mockMvc.perform(request);
    }

    private static String body(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString();
    }

    private String column(UUID evidenceId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM digital_evidence WHERE id = ?", String.class, evidenceId);
    }

    private CustodyCase caseWithMembers(Operator creator, Operator... members) {
        CustodyCase custodyCase = custodyCases.saveAndFlush(
                CustodyCase.create("Leak audit case", null, null, null, null, CasePriority.HIGH, creator));
        memberships.save(CaseMembership.assign(custodyCase, creator, creator));
        for (Operator member : members) {
            memberships.save(CaseMembership.assign(custodyCase, member, creator));
        }
        memberships.flush();
        return custodyCase;
    }

    private Operator saveOperator(String username, OperatorRole role) {
        return operators.saveAndFlush(Operator.create(
                username, username + "@example.com", passwordEncoder.encode(PASSWORD), "First", "Last", role));
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
