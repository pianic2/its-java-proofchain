package it.itsprodigi.proofchain.evidence.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import it.itsprodigi.proofchain.evidence.application.EvidenceDownloadDescriptor;
import it.itsprodigi.proofchain.evidence.application.EvidenceHashing;
import it.itsprodigi.proofchain.evidence.application.EvidenceStorageKeyFactory;
import it.itsprodigi.proofchain.evidence.application.EvidenceStoragePort;
import it.itsprodigi.proofchain.evidence.application.StagedEvidence;
import it.itsprodigi.proofchain.evidence.domain.AcquisitionMethod;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.domain.SourceType;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceContentMetadata;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceRepository;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class EvidenceReadWebMvcIT extends PostgreSqlIntegrationTest {

    private static final byte[] CONTENT = "read-side-🔐".getBytes(StandardCharsets.UTF_8);

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void configureStorage(DynamicPropertyRegistry registry) {
        registry.add("proofchain.storage.root", () -> storageRoot.toString());
        registry.add("proofchain.storage.max-file-size", () -> "1KB");
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
    private EvidenceStoragePort storage;

    @Autowired
    private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager entityManager;

    private Operator admin;
    private Operator manager;
    private Operator officer;
    private Operator auditor;
    private Operator outsider;

    @BeforeEach
    void setUp() throws IOException {
        cleanDatabase();
        cleanStorage();
        admin = saveOperator("admin-read", OperatorRole.ADMIN);
        manager = saveOperator("manager-read", OperatorRole.CASE_MANAGER);
        officer = saveOperator("officer-read", OperatorRole.EVIDENCE_OFFICER);
        auditor = saveOperator("auditor-read", OperatorRole.AUDITOR);
        outsider = saveOperator("outsider-read", OperatorRole.AUDITOR);
    }

    @AfterEach
    void tearDown() throws IOException {
        cleanDatabase();
        cleanStorage();
    }

    @Test
    void listsExactSummariesInFixedOrderForMembersAndAdminAndRejectsInvalidQueries() throws Exception {
        CustodyCase custodyCase = caseWithMembers("Read list", manager, officer, auditor);
        UUID firstId = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID secondId = UUID.fromString("00000000-0000-4000-8000-000000000002");
        UUID olderId = UUID.fromString("00000000-0000-4000-8000-000000000000");
        DigitalEvidence first = saveEvidence(firstId, custodyCase, "READ-1", "First", "first.bin", CONTENT);
        first.release();
        evidences.saveAndFlush(first);
        saveEvidence(secondId, custodyCase, "READ-2", "Second", "second.bin", new byte[] {2});
        saveEvidence(olderId, custodyCase, "READ-OLD", "Older", "older.bin", new byte[] {3});
        Instant sameTimestamp = Instant.parse("2026-01-01T00:00:02Z");
        jdbc.update(
                "UPDATE digital_evidence SET created_at = ?, updated_at = ? WHERE id IN (?, ?)",
                Timestamp.from(sameTimestamp),
                Timestamp.from(sameTimestamp),
                firstId,
                secondId);
        jdbc.update(
                "UPDATE digital_evidence SET created_at = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(sameTimestamp.minusSeconds(1)),
                Timestamp.from(sameTimestamp.minusSeconds(1)),
                olderId);
        custodyCase.close();
        custodyCases.saveAndFlush(custodyCase);
        entityManager.clear();

        mockMvc.perform(get("/api/v1/cases/{caseId}/evidences", custodyCase.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(auditor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(5)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.content[0].*", hasSize(18)))
                .andExpect(jsonPath("$.content[0].id").value(firstId.toString()))
                .andExpect(jsonPath("$.content[0].status").value("RELEASED"))
                .andExpect(jsonPath("$.content[0].currentHolder").isEmpty())
                .andExpect(jsonPath("$.content[0].uploadedBy.*", hasSize(6)))
                .andExpect(jsonPath("$.content[0].description").doesNotExist())
                .andExpect(jsonPath("$.content[0].sourceDescription").doesNotExist())
                .andExpect(jsonPath("$.content[0].acquisitionNotes").doesNotExist())
                .andExpect(jsonPath("$.content[0].storageKey").doesNotExist())
                .andExpect(jsonPath("$.content[0].version").doesNotExist())
                .andExpect(jsonPath("$.content[1].id").value(secondId.toString()))
                .andExpect(jsonPath("$.content[2].id").value(olderId.toString()));

        mockMvc.perform(get("/api/v1/cases/{caseId}/evidences", custodyCase.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/cases/{caseId}/evidences", custodyCase.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(outsider)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/cases/{caseId}/evidences", custodyCase.getId())
                        .param("page", "-1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(auditor)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/cases/{caseId}/evidences", custodyCase.getId())
                        .param("size", "0")
                        .header(HttpHeaders.AUTHORIZATION, bearer(auditor)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/cases/{caseId}/evidences", custodyCase.getId())
                        .param("size", "101")
                        .header(HttpHeaders.AUTHORIZATION, bearer(auditor)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/cases/{caseId}/evidences", custodyCase.getId())
                        .param("sort", "")
                        .header(HttpHeaders.AUTHORIZATION, bearer(auditor)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/cases/{caseId}/evidences", custodyCase.getId())
                        .param("sort", "createdAt,desc")
                        .header(HttpHeaders.AUTHORIZATION, bearer(auditor)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsCompleteClosedReleasedEvidenceToEveryMemberWithoutVisibilityLeak() throws Exception {
        CustodyCase custodyCase = caseWithMembers("Read detail", manager, officer, auditor);
        DigitalEvidence evidence = saveEvidence(
                UUID.fromString("10000000-0000-4000-8000-000000000001"),
                custodyCase,
                "DETAIL",
                "Released detail",
                "detail.bin",
                CONTENT);
        evidence.release();
        evidences.saveAndFlush(evidence);
        custodyCase.close();
        custodyCases.saveAndFlush(custodyCase);

        for (Operator reader : new Operator[] {admin, manager, officer, auditor}) {
            mockMvc.perform(get("/api/v1/evidences/{evidenceId}", evidence.getId())
                            .header(HttpHeaders.AUTHORIZATION, bearer(reader)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.*", hasSize(28)))
                    .andExpect(jsonPath("$.id").value(evidence.getId().toString()))
                    .andExpect(jsonPath("$.status").value("RELEASED"))
                    .andExpect(jsonPath("$.currentHolder").isEmpty())
                    .andExpect(jsonPath("$.storageKey").doesNotExist())
                    .andExpect(jsonPath("$.version").doesNotExist());
        }
        mockMvc.perform(get("/api/v1/evidences/{evidenceId}", evidence.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(outsider)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/evidences/{evidenceId}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void streamsCompleteBytesWithSafeAsciiUnicodeAndLegacyFilenamesWhileIgnoringRange() throws Exception {
        CustodyCase custodyCase = caseWithMembers("Read download", manager, auditor);
        DigitalEvidence evidence = saveEvidence(
                UUID.fromString("20000000-0000-4000-8000-000000000001"),
                custodyCase,
                "DOWNLOAD",
                "Download",
                "report.pdf",
                CONTENT);
        evidence.release();
        evidences.saveAndFlush(evidence);
        custodyCase.close();
        custodyCases.saveAndFlush(custodyCase);

        ResultActions ascii = download(evidence.getId(), auditor, "bytes=1-3")
                .andExpect(status().isOk())
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, CONTENT.length))
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_RANGE))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(content().bytes(CONTENT));
        assertThat(filename(ascii)).isEqualTo("report.pdf");

        jdbc.update(
                "UPDATE digital_evidence SET original_filename = ?, media_type = ? WHERE id = ?",
                "résumé 🔐.pdf",
                "application/pdf",
                evidence.getId());
        ResultActions unicode = download(evidence.getId(), auditor, null)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(CONTENT));
        assertThat(filename(unicode)).isEqualTo("résumé 🔐.pdf");

        jdbc.update("UPDATE digital_evidence SET media_type = ? WHERE id = ?", "not a media type", evidence.getId());
        ResultActions fallback = download(evidence.getId(), auditor, null)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(content().bytes(CONTENT));
        assertThat(filename(fallback)).isEqualTo("résumé 🔐.pdf");

        DigitalEvidenceContentMetadata metadata =
                evidences.findContentMetadataById(evidence.getId()).orElseThrow();
        EvidenceDownloadDescriptor legacyDescriptor = new EvidenceDownloadDescriptor(
                evidence.getId(),
                "../../legacy\r\nInjected.txt",
                metadata.getMediaType(),
                metadata.getFileSize(),
                metadata.getStorageKey());
        assertThat(EvidenceReadController.safeFilename(legacyDescriptor))
                .isEqualTo("legacy__Injected.txt")
                .doesNotContain("/", "\\", "\r", "\n", "..");
        mockMvc.perform(get("/api/v1/evidences/{evidenceId}/download", evidence.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(outsider)))
                .andExpect(status().isNotFound());
    }

    @Test
    void mapsMissingAndInconsistentVisibleContentWithoutLeakingStorageDetails() throws Exception {
        CustodyCase custodyCase = caseWithMembers("Read failures", manager, auditor);
        DigitalEvidence inconsistent = saveEvidence(
                UUID.fromString("30000000-0000-4000-8000-000000000001"),
                custodyCase,
                "MISMATCH",
                "Mismatch",
                "mismatch.bin",
                CONTENT);
        jdbc.update("UPDATE digital_evidence SET file_size = file_size + 1 WHERE id = ?", inconsistent.getId());

        mockMvc.perform(get("/api/v1/evidences/{evidenceId}/download", inconsistent.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(auditor)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/evidence-file-unavailable"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("cases/"))))
                .andExpect(content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("mismatch.bin"))))
                .andExpect(content()
                        .string(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString(storageRoot.toString()))));

        DigitalEvidence missing = saveEvidence(
                UUID.fromString("30000000-0000-4000-8000-000000000002"),
                custodyCase,
                "MISSING",
                "Missing",
                "missing.bin",
                new byte[] {1, 2, 3});
        DigitalEvidenceContentMetadata metadata =
                evidences.findContentMetadataById(missing.getId()).orElseThrow();
        Files.delete(storageRoot.resolve(metadata.getStorageKey()));

        mockMvc.perform(get("/api/v1/evidences/{evidenceId}/download", missing.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(auditor)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/evidence-file-unavailable"));
    }

    @Test
    void documentsTheFourCanonicalEvidenceOperationsAndExactResponsePolicies() throws Exception {
        String caseEvidencePath = "/api/v1/cases/{caseId}/evidences";
        String detailPath = "/api/v1/evidences/{evidenceId}";
        String downloadPath = "/api/v1/evidences/{evidenceId}/download";
        String registration = "$.paths['" + caseEvidencePath + "'].post";
        String list = "$.paths['" + caseEvidencePath + "'].get";
        String detail = "$.paths['" + detailPath + "'].get";
        String download = "$.paths['" + downloadPath + "'].get";
        // The complete path/method allowlist for the whole API lives in the single authoritative
        // it.itsprodigi.proofchain.ApiSurfaceContractIT; this suite pins only the response policies of the four
        // read-side operations it owns, so the surface is enumerated in exactly one place.
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(registration + ".operationId").value("registerDigitalEvidence"))
                .andExpect(jsonPath(list + ".responses['200']").exists())
                .andExpect(jsonPath(list + ".responses['400']").exists())
                .andExpect(jsonPath(list + ".responses['401']").exists())
                .andExpect(jsonPath(list + ".responses['404']").exists())
                .andExpect(jsonPath(list + ".responses['403']").doesNotExist())
                .andExpect(jsonPath(detail + ".responses['200']").exists())
                .andExpect(jsonPath(detail + ".responses['400']").exists())
                .andExpect(jsonPath(detail + ".responses['401']").exists())
                .andExpect(jsonPath(detail + ".responses['404']").exists())
                .andExpect(jsonPath(detail + ".responses['403']").doesNotExist())
                .andExpect(jsonPath(download + ".responses['200'].headers['Content-Disposition']")
                        .exists())
                .andExpect(jsonPath(download + ".responses['200'].headers['Content-Length']")
                        .exists())
                .andExpect(jsonPath(download + ".responses['200'].content['application/octet-stream'].schema.format")
                        .value("binary"))
                .andExpect(jsonPath(download + ".responses['400']").exists())
                .andExpect(jsonPath(download + ".responses['401']").exists())
                .andExpect(jsonPath(download + ".responses['404']").exists())
                .andExpect(jsonPath(download + ".responses['500']").exists())
                .andExpect(jsonPath("$.components.schemas.EvidenceSummaryResponse.properties.*", hasSize(18)))
                .andExpect(jsonPath("$.components.schemas.EvidencePageResponse.properties.*", hasSize(5)));
    }

    private ResultActions download(UUID evidenceId, Operator reader, String range) throws Exception {
        var requestBuilder = get("/api/v1/evidences/{evidenceId}/download", evidenceId)
                .header(HttpHeaders.AUTHORIZATION, bearer(reader));
        if (range != null) {
            requestBuilder.header(HttpHeaders.RANGE, range);
        }
        return mockMvc.perform(requestBuilder);
    }

    private static String filename(ResultActions result) {
        String header = result.andReturn().getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(header).isNotNull().startsWith("attachment;");
        return ContentDisposition.parse(header).getFilename();
    }

    private CustodyCase caseWithMembers(String title, Operator creator, Operator... additionalMembers) {
        CustodyCase custodyCase = custodyCases.saveAndFlush(
                CustodyCase.create(title, null, null, null, null, CasePriority.HIGH, creator));
        memberships.save(CaseMembership.assign(custodyCase, creator, creator));
        for (Operator member : additionalMembers) {
            memberships.save(CaseMembership.assign(custodyCase, member, creator));
        }
        memberships.flush();
        return custodyCase;
    }

    private DigitalEvidence saveEvidence(
            UUID evidenceId,
            CustodyCase custodyCase,
            String referenceTag,
            String title,
            String filename,
            byte[] bytes) {
        String storageKey = EvidenceStorageKeyFactory.forEvidence(custodyCase.getId(), evidenceId);
        String contentSha256 = EvidenceHashing.contentSha256(bytes);
        DigitalEvidence evidence = DigitalEvidence.create(
                evidenceId,
                custodyCase,
                officer,
                manager,
                referenceTag,
                title,
                "Long detail excluded from summaries",
                SourceType.DEVICE,
                "Source context excluded from summaries",
                "Manufacturer",
                "Model",
                "Serial",
                "disk0",
                AcquisitionMethod.PHYSICAL,
                "Evidence room",
                "Imager",
                "1.0",
                "Notes excluded from summaries",
                Instant.parse("2026-01-01T00:00:00Z"),
                filename,
                MediaType.APPLICATION_OCTET_STREAM_VALUE,
                bytes.length,
                contentSha256,
                EvidenceHashing.contextualSha256(custodyCase.getId(), evidenceId, contentSha256),
                storageKey);
        evidences.saveAndFlush(evidence);
        StagedEvidence staged = storage.stage(storageKey, new ByteArrayInputStream(bytes));
        storage.finalizeStaged(staged);
        return evidence;
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
