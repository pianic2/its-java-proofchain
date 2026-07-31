package it.itsprodigi.proofchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
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
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bounded, informative performance smoke over a purely synthetic dataset.
 *
 * <p><strong>This suite establishes no SLA, no production capacity and no throughput guarantee.</strong> It runs one
 * Spring context against one PostgreSQL Testcontainer, in-process through MockMvc, on whatever machine happens to be
 * executing the build. The numbers it prints are a single observation of that arrangement and nothing else. They exist
 * so that a correctness, memory, timeout or resource-exhaustion defect that only appears with a non-trivial amount of
 * data has somewhere to show up. Only invariants are asserted; no timing is ever asserted.
 *
 * <p>The dataset is generated in-process and contains no real evidence: 10 custody cases, 50 registered evidences and
 * roughly 500 custody events, with 1 KiB files plus one file just under the upload limit configured for this context.
 *
 * <p>The class is deliberately excluded from the default Failsafe run and is only executed by the {@code
 * performance-smoke} Maven profile, so it can never make the canonical build slow, blocking or flaky. No pre-existing
 * test is excluded or weakened by that arrangement.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BoundedPerformanceSmokeIT extends PostgreSqlIntegrationTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(BoundedPerformanceSmokeIT.class);

    private static final String PASSWORD = "smoke-Passw0rd!-2026";
    private static final int CASES = 10;
    private static final int EVIDENCES_PER_CASE = 5;
    private static final int COMMANDS_PER_EVIDENCE = 9;
    private static final int MEASUREMENT_REPETITIONS = 10;

    /** Upload limit configured for this context. The near-limit file is sized against exactly this value. */
    private static final int CONFIGURED_UPLOAD_LIMIT_BYTES = 2 * 1024 * 1024;

    private static final int SMALL_FILE_BYTES = 1024;
    private static final int NEAR_LIMIT_FILE_BYTES = CONFIGURED_UPLOAD_LIMIT_BYTES - 1024;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void configureLimits(DynamicPropertyRegistry registry) {
        registry.add("proofchain.storage.root", () -> storageRoot.toString());
        registry.add("proofchain.storage.max-file-size", () -> CONFIGURED_UPLOAD_LIMIT_BYTES + "B");
        registry.add("spring.servlet.multipart.max-file-size", () -> (CONFIGURED_UPLOAD_LIMIT_BYTES + 1024) + "B");
        registry.add("spring.servlet.multipart.max-request-size", () -> (CONFIGURED_UPLOAD_LIMIT_BYTES + 8192) + "B");
    }

    @Autowired
    private MockMvc mockMvc;

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

    @Test
    void measuresRepresentativeOperationsOverABoundedSyntheticDatasetWithoutClaimingAnySla() throws Exception {
        cleanDatabase();
        cleanStorage();

        Operator manager = saveOperator("smoke-manager", OperatorRole.CASE_MANAGER);
        Operator holderA = saveOperator("smoke-holder-a", OperatorRole.EVIDENCE_OFFICER);
        Operator holderB = saveOperator("smoke-holder-b", OperatorRole.EVIDENCE_OFFICER);

        long seedStart = System.nanoTime();
        String token = login(manager);
        List<UUID> caseIds = new ArrayList<>();
        List<UUID> evidenceIds = new ArrayList<>();
        for (int caseIndex = 0; caseIndex < CASES; caseIndex++) {
            CustodyCase custodyCase = caseWithMembers("Smoke case " + caseIndex, manager, holderA, holderB);
            caseIds.add(custodyCase.getId());
            for (int evidenceIndex = 0; evidenceIndex < EVIDENCES_PER_CASE; evidenceIndex++) {
                boolean nearLimit = caseIndex == 0 && evidenceIndex == 0;
                byte[] content = syntheticContent(
                        caseIndex, evidenceIndex, nearLimit ? NEAR_LIMIT_FILE_BYTES : SMALL_FILE_BYTES);
                evidenceIds.add(register(
                        custodyCase.getId(),
                        token,
                        "SMOKE-%d-%d".formatted(caseIndex, evidenceIndex),
                        content,
                        holderA));
            }
        }
        for (UUID evidenceId : evidenceIds) {
            Operator holder = holderA;
            for (int command = 0; command < COMMANDS_PER_EVIDENCE; command++) {
                if (command % 2 == 0) {
                    holder = holder == holderA ? holderB : holderA;
                    transfer(evidenceId, token, holder);
                } else {
                    updateMetadata(evidenceId, token, command);
                }
            }
        }
        long seedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - seedStart);

        UUID sampleCaseId = caseIds.getFirst();
        UUID sampleEvidenceId = evidenceIds.getFirst();

        Map<String, long[]> samples = new LinkedHashMap<>();
        samples.put("login", measure(() -> post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(manager))));
        samples.put("caseList", measure(() -> get("/api/v1/cases?page=0&size=20")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)));
        samples.put("evidenceList", measure(() -> get("/api/v1/cases/{caseId}/evidences?page=0&size=20", sampleCaseId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)));
        samples.put("timeline", measure(() -> get("/api/v1/evidences/{id}/events?page=0&size=20", sampleEvidenceId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)));
        samples.put("chainVerification", measure(() -> post("/api/v1/evidences/{id}/verify-chain", sampleEvidenceId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)));
        samples.put(
                "integrityVerification", measure(() -> post("/api/v1/evidences/{id}/verify-integrity", sampleEvidenceId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)));

        long evidenceCount = evidences.count();
        long caseCount = custodyCases.count();
        Long eventCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM custody_events", Long.class);

        StringBuilder report = new StringBuilder("PERFORMANCE-SMOKE informative-only no-sla dataset")
                .append(" cases=")
                .append(caseCount)
                .append(" evidences=")
                .append(evidenceCount)
                .append(" custodyEvents=")
                .append(eventCount)
                .append(" smallFileBytes=")
                .append(SMALL_FILE_BYTES)
                .append(" nearLimitFileBytes=")
                .append(NEAR_LIMIT_FILE_BYTES)
                .append(" configuredUploadLimitBytes=")
                .append(CONFIGURED_UPLOAD_LIMIT_BYTES)
                .append(" seedMillis=")
                .append(seedMillis)
                .append(" repetitions=")
                .append(MEASUREMENT_REPETITIONS);
        samples.forEach((operation, values) -> {
            long[] sorted = values.clone();
            Arrays.sort(sorted);
            report.append(' ')
                    .append(operation)
                    .append("MillisMin=")
                    .append(sorted[0])
                    .append(' ')
                    .append(operation)
                    .append("MillisMedian=")
                    .append(sorted[sorted.length / 2])
                    .append(' ')
                    .append(operation)
                    .append("MillisMax=")
                    .append(sorted[sorted.length - 1]);
        });
        LOGGER.info("{}", report);

        // The only assertions are correctness invariants; no timing is asserted anywhere.
        assertThat(caseCount).isEqualTo(CASES);
        assertThat(evidenceCount).isEqualTo((long) CASES * EVIDENCES_PER_CASE);
        assertThat(eventCount)
                .as("bounded synthetic dataset of roughly 500 custody events")
                .isBetween(500L, 560L);
        assertThat(storedContentBytes())
                .as("the near-limit upload must be stored whole")
                .contains(NEAR_LIMIT_FILE_BYTES);

        // Every chain in the dataset must still verify after the full command load.
        for (UUID evidenceId : evidenceIds) {
            String verification = mockMvc.perform(post("/api/v1/evidences/{id}/verify-chain", evidenceId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(JSON.readTree(verification).get("valid").booleanValue())
                    .as("chain of evidence %s must remain valid", evidenceId)
                    .isTrue();
        }

        cleanDatabase();
        cleanStorage();
    }

    private long[] measure(java.util.function.Supplier<RequestBuilder> request) throws Exception {
        long[] millis = new long[MEASUREMENT_REPETITIONS];
        for (int index = 0; index < MEASUREMENT_REPETITIONS; index++) {
            long start = System.nanoTime();
            mockMvc.perform(request.get()).andExpect(status().is2xxSuccessful());
            millis[index] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        }
        return millis;
    }

    private String login(Operator operator) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(operator)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JSON.readTree(response).get("accessToken").stringValue();
    }

    private static String loginBody(Operator operator) {
        return JSON.writeValueAsString(Map.of("username", operator.getUsername(), "password", PASSWORD));
    }

    private UUID register(UUID caseId, String token, String referenceTag, byte[] content, Operator holder)
            throws Exception {
        MockPart metadata =
                new MockPart("metadata", metadataJson(referenceTag, holder).getBytes(StandardCharsets.UTF_8));
        metadata.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        MockMultipartFile file =
                new MockMultipartFile("file", "synthetic.bin", MediaType.APPLICATION_OCTET_STREAM_VALUE, content);
        MockPart filePart = new MockPart("file", "synthetic.bin", content);
        filePart.getHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE);
        MockMultipartHttpServletRequestBuilder request =
                (MockMultipartHttpServletRequestBuilder) multipart("/api/v1/cases/{caseId}/evidences", caseId)
                        .part(metadata)
                        .part(filePart)
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        String response = mockMvc.perform(request)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JSON.readTree(response).get("id").stringValue());
    }

    private void transfer(UUID evidenceId, String token, Operator newHolder) throws Exception {
        mockMvc.perform(post("/api/v1/evidences/{id}/transfer", evidenceId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "newHolderId", newHolder.getId().toString(), "reason", "Synthetic custody rotation"))))
                .andExpect(status().isOk());
    }

    private void updateMetadata(UUID evidenceId, String token, int revision) throws Exception {
        mockMvc.perform(patch("/api/v1/evidences/{id}/metadata", evidenceId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "title",
                                "Synthetic artefact revision " + revision,
                                "reason",
                                "Synthetic metadata revision"))))
                .andExpect(status().isOk());
    }

    private static String metadataJson(String referenceTag, Operator holder) {
        return """
                {
                  "referenceTag": "%s",
                  "title": "Synthetic artefact",
                  "sourceType": "DEVICE",
                  "acquisitionMethod": "LOGICAL",
                  "initialHolderId": "%s"
                }
                """.formatted(referenceTag, holder.getId());
    }

    /** Deterministic, non-compressible-looking synthetic bytes. No real evidence is ever used. */
    private static byte[] syntheticContent(int caseIndex, int evidenceIndex, int size) {
        byte[] content = new byte[size];
        int seed = caseIndex * 31 + evidenceIndex;
        for (int index = 0; index < size; index++) {
            content[index] = (byte) ((index * 131 + seed * 17 + 7) & 0xFF);
        }
        return content;
    }

    private List<Integer> storedContentBytes() throws IOException {
        try (Stream<Path> paths = Files.walk(storageRoot)) {
            return paths.filter(path -> path.getFileName().toString().equals("content.bin"))
                    .map(path -> {
                        try {
                            return (int) Files.size(path);
                        } catch (IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .toList();
        }
    }

    private CustodyCase caseWithMembers(String title, Operator creator, Operator... members) {
        CustodyCase custodyCase = custodyCases.saveAndFlush(
                CustodyCase.create(title, null, null, null, null, CasePriority.MEDIUM, creator));
        memberships.save(CaseMembership.assign(custodyCase, creator, creator));
        for (Operator member : members) {
            memberships.save(CaseMembership.assign(custodyCase, member, creator));
        }
        memberships.flush();
        return custodyCase;
    }

    private Operator saveOperator(String username, OperatorRole role) {
        return operators.saveAndFlush(Operator.create(
                username,
                username.toLowerCase(Locale.ROOT) + "@example.com",
                passwordEncoder.encode(PASSWORD),
                "First",
                "Last",
                role));
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
