package it.itsprodigi.proofchain.evidence.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.itsprodigi.proofchain.auth.application.JwtTokenService;
import it.itsprodigi.proofchain.custodycase.application.CaseAccessService;
import it.itsprodigi.proofchain.custodycase.domain.CaseMembership;
import it.itsprodigi.proofchain.custodycase.domain.CasePriority;
import it.itsprodigi.proofchain.custodycase.domain.CaseStatus;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.custodycase.persistence.CustodyCaseRepository;
import it.itsprodigi.proofchain.custodyevent.domain.CustodyEvent;
import it.itsprodigi.proofchain.custodyevent.domain.EventType;
import it.itsprodigi.proofchain.custodyevent.persistence.CustodyEventRepository;
import it.itsprodigi.proofchain.custodyevent.protocol.CanonicalCustodyEvent;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventCanonicalizer;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventHashing;
import it.itsprodigi.proofchain.custodyevent.protocol.EvidenceRegisteredPayload;
import it.itsprodigi.proofchain.evidence.application.EvidenceMapper;
import it.itsprodigi.proofchain.evidence.application.EvidenceStorageFailureException;
import it.itsprodigi.proofchain.evidence.application.EvidenceStoragePort;
import it.itsprodigi.proofchain.evidence.application.StagedEvidence;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceRepository;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.domain.OperatorStatus;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
class EvidenceRegistrationWebMvcIT extends PostgreSqlIntegrationTest {

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void configureEvidenceStorage(DynamicPropertyRegistry registry) {
        registry.add("proofchain.storage.root", () -> storageRoot.toString());
        registry.add("proofchain.storage.max-file-size", () -> "64B");
        registry.add("spring.servlet.multipart.max-file-size", () -> "1KB");
        registry.add("spring.servlet.multipart.max-request-size", () -> "4KB");
    }

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

    @MockitoSpyBean
    private DigitalEvidenceRepository evidences;

    @MockitoSpyBean
    private CustodyEventRepository events;

    @MockitoSpyBean
    private EvidenceMapper mapper;

    @MockitoSpyBean
    private CaseAccessService access;

    @MockitoSpyBean
    private EvidenceStoragePort storage;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Operator admin;
    private Operator manager;
    private Operator officer;
    private Operator otherOfficer;
    private Operator auditor;

    @BeforeEach
    void setUp() throws IOException {
        cleanDatabase();
        cleanStorage();
        admin = saveOperator("admin", OperatorRole.ADMIN);
        manager = saveOperator("manager", OperatorRole.CASE_MANAGER);
        officer = saveOperator("officer", OperatorRole.EVIDENCE_OFFICER);
        otherOfficer = saveOperator("other-officer", OperatorRole.EVIDENCE_OFFICER);
        auditor = saveOperator("auditor", OperatorRole.AUDITOR);
    }

    @AfterEach
    void tearDown() throws IOException {
        cleanDatabase();
        cleanStorage();
    }

    @Test
    void registersExactMultipartWithCanonicalResponseLocationHashesFilenameAndMediaFallback() throws Exception {
        CustodyCase custodyCase = caseWithMembers("Evidence case", manager, officer);
        byte[] contentBytes = "binary-🔐".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(request(
                        custodyCase,
                        manager,
                        metadata(metadataJson(" evidence-01 ", officer.getId()), "application/json;charset=UTF-8"),
                        file("C:\\fakepath\\Report.PDF", "@@/invalid", contentBytes)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern("https?://[^/]+/api/v1/evidences/[0-9a-f-]{36}")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.*", hasSize(28)))
                .andExpect(jsonPath("$.caseId").value(custodyCase.getId().toString()))
                .andExpect(jsonPath("$.referenceTag").value("EVIDENCE-01"))
                .andExpect(jsonPath("$.status").value("IN_CUSTODY"))
                .andExpect(jsonPath("$.currentHolder.id").value(officer.getId().toString()))
                .andExpect(jsonPath("$.uploadedBy.id").value(manager.getId().toString()))
                .andExpect(jsonPath("$.originalFilename").value("Report.PDF"))
                .andExpect(jsonPath("$.fileExtension").value("pdf"))
                .andExpect(jsonPath("$.mediaType").value(MediaType.APPLICATION_OCTET_STREAM_VALUE))
                .andExpect(jsonPath("$.fileSize").value(contentBytes.length))
                .andExpect(jsonPath("$.contentSha256")
                        .value("06411a793912054045dca9663d1bd4c937a4e812bbe479b19813b55ec72f9ab4"))
                .andExpect(jsonPath("$.contextualSha256").isString())
                .andExpect(jsonPath("$.storageKey").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist())
                .andExpect(jsonPath("$.custodyEventCount").doesNotExist())
                .andExpect(jsonPath("$.custodyChainHeadHash").doesNotExist())
                .andExpect(jsonPath("$.downloadUrl").doesNotExist());

        DigitalEvidence persisted = evidences.findAll().getFirst();
        List<CustodyEvent> timeline = events.findAllByEvidenceIdOrderBySequenceNumberAsc(persisted.getId());
        assertThat(timeline).hasSize(1);
        CustodyEvent genesis = timeline.getFirst();
        EvidenceRegisteredPayload payload = registrationPayload(persisted);
        assertThat(persisted.getId().version()).isEqualTo(4);
        assertThat(persisted.getContextualSha256()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(persisted.getCustodyEventCount()).isEqualTo(1);
        assertThat(persisted.getCustodyChainHeadHash()).isEqualTo(genesis.getEventHash());
        assertThat(persisted.getCreatedAt()).isEqualTo(persisted.getUpdatedAt()).isEqualTo(genesis.getOccurredAt());
        assertThat(genesis.getEventType()).isEqualTo(EventType.EVIDENCE_REGISTERED);
        assertThat(genesis.getSequenceNumber()).isEqualTo(1);
        assertThat(genesis.getPreviousHash()).isEqualTo(CustodyEventHashing.ZERO_HASH);
        JsonMapper json = JsonMapper.builder().build();
        assertThat(json.readTree(genesis.getPayloadJson()))
                .isEqualTo(json.readTree(CustodyEventCanonicalizer.canonicalizePayload(payload)));
        assertThat(genesis.getEventHash())
                .isEqualTo(CustodyEventHashing.eventHash(canonicalEvent(genesis, persisted, manager, payload)));
        assertThat(readOnlyStoredContent()).containsExactly(contentBytes);
    }

    @Test
    void enforcesRoleMembershipVisibilityAndOfficerSelfCustody() throws Exception {
        CustodyCase custodyCase = caseWithMembers("Role case", manager, officer, auditor, otherOfficer);

        mockMvc.perform(request(
                        custodyCase,
                        officer,
                        metadata(metadataJson("OFFICER", officer.getId())),
                        file("a.bin", "application/octet-stream", new byte[] {1})))
                .andExpect(status().isCreated());
        mockMvc.perform(request(
                        custodyCase,
                        officer,
                        metadata(metadataJson("OTHER", otherOfficer.getId())),
                        file("b.bin", "application/octet-stream", new byte[] {2})))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/access-denied"));
        mockMvc.perform(request(
                        custodyCase,
                        auditor,
                        metadata(metadataJson("AUDIT", officer.getId())),
                        file("c.bin", "application/octet-stream", new byte[] {3})))
                .andExpect(status().isForbidden());

        Operator nonMemberManager = saveOperator("non-member-manager", OperatorRole.CASE_MANAGER);
        mockMvc.perform(request(
                        custodyCase,
                        nonMemberManager,
                        metadata(metadataJson("HIDDEN", officer.getId())),
                        file("d.bin", "application/octet-stream", new byte[] {4})))
                .andExpect(status().isNotFound());

        CustodyCase adminGlobalCase = caseWithMembers("Admin global", manager, officer);
        mockMvc.perform(request(
                        adminGlobalCase,
                        admin,
                        metadata(metadataJson("ADMIN", officer.getId())),
                        file("e.bin", "application/octet-stream", new byte[] {5})))
                .andExpect(status().isCreated());
    }

    @Test
    void rejectsClosedCaseAndEveryIneligibleHolderWithStableConflicts() throws Exception {
        CustodyCase custodyCase = caseWithMembers("Holder case", manager, officer, auditor);
        Operator suspended = saveOperator("suspended", OperatorRole.EVIDENCE_OFFICER);
        suspended.changeStatus(OperatorStatus.SUSPENDED);
        operators.saveAndFlush(suspended);
        memberships.saveAndFlush(CaseMembership.assign(custodyCase, suspended, manager));
        Operator nonMember = saveOperator("non-member", OperatorRole.EVIDENCE_OFFICER);

        for (Operator holder : new Operator[] {auditor, suspended, nonMember}) {
            mockMvc.perform(request(
                            custodyCase,
                            manager,
                            metadata(metadataJson("TAG-" + holder.getUsername(), holder.getId())),
                            file("e.bin", "application/octet-stream", new byte[] {1})))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/holder-not-eligible"));
        }
        mockMvc.perform(request(
                        custodyCase,
                        manager,
                        metadata(metadataJson("MISSING", UUID.randomUUID())),
                        file("e.bin", "application/octet-stream", new byte[] {1})))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/holder-not-eligible"));

        custodyCase.close();
        custodyCases.saveAndFlush(custodyCase);
        mockMvc.perform(request(
                        custodyCase,
                        manager,
                        metadata(metadataJson("CLOSED", officer.getId())),
                        file("e.bin", "application/octet-stream", new byte[] {1})))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/case-closed"));
    }

    @Test
    void rejectsUnknownJsonOrMultipartPartsAndWrongMetadataMediaType() throws Exception {
        CustodyCase custodyCase = caseWithMembers("Strict case", manager, officer);
        String withDerivedId = metadataJson("STRICT-1", officer.getId())
                .replace("\"initialHolderId\"", "\"id\":\"" + UUID.randomUUID() + "\",\"initialHolderId\"");

        mockMvc.perform(request(
                        custodyCase,
                        manager,
                        metadata(withDerivedId),
                        file("e.bin", "application/octet-stream", new byte[] {1})))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/validation-error"));

        MockMultipartHttpServletRequestBuilder unknownPart = request(
                        custodyCase,
                        manager,
                        metadata(metadataJson("STRICT-2", officer.getId())),
                        file("e.bin", "application/octet-stream", new byte[] {1}))
                .part(new MockPart("unexpected", "value".getBytes(StandardCharsets.UTF_8)));
        mockMvc.perform(unknownPart).andExpect(status().isBadRequest());

        mockMvc.perform(request(
                        custodyCase,
                        manager,
                        metadata(metadataJson("STRICT-3", officer.getId()), "application/problem+json"),
                        file("e.bin", "application/octet-stream", new byte[] {1})))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/validation-error"));
        mockMvc.perform(request(
                        custodyCase,
                        manager,
                        metadata(metadataJson("STRICT-4", officer.getId()), "application/json;profile=forensic"),
                        file("e.bin", "application/octet-stream", new byte[] {1})))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mapsEmptyAndOversizedEvidenceAndRejectsUnsafeFilename() throws Exception {
        CustodyCase custodyCase = caseWithMembers("Boundary case", manager, officer);

        mockMvc.perform(request(
                        custodyCase,
                        manager,
                        metadata(metadataJson("EMPTY", officer.getId())),
                        file("e.bin", "application/octet-stream", new byte[0])))
                .andExpect(status().isBadRequest());
        mockMvc.perform(request(
                        custodyCase,
                        manager,
                        metadata(metadataJson("LARGE", officer.getId())),
                        file("e.bin", "application/octet-stream", new byte[65])))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/payload-too-large"));
        mockMvc.perform(request(
                        custodyCase,
                        manager,
                        metadata(metadataJson("BAD-NAME", officer.getId())),
                        file("../", "application/octet-stream", new byte[] {1})))
                .andExpect(status().isBadRequest());
        assertThat(evidences.count()).isZero();
        assertThat(storedContentCount()).isZero();
    }

    @Test
    void enforcesCaseScopedNormalizedReferenceTagUniqueness() throws Exception {
        CustodyCase firstCase = caseWithMembers("First duplicate case", manager, officer);
        CustodyCase secondCase = caseWithMembers("Second duplicate case", manager, officer);

        mockMvc.perform(request(
                        firstCase,
                        manager,
                        metadata(metadataJson(" tag-01 ", officer.getId())),
                        file("a.bin", "application/octet-stream", new byte[] {1})))
                .andExpect(status().isCreated());
        mockMvc.perform(request(
                        firstCase,
                        manager,
                        metadata(metadataJson("TAG-01", officer.getId())),
                        file("b.bin", "application/octet-stream", new byte[] {2})))
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.type").value("https://proofchain.dev/problems/duplicate-evidence-reference-tag"));
        mockMvc.perform(request(
                        secondCase,
                        manager,
                        metadata(metadataJson("tag-01", officer.getId())),
                        file("c.bin", "application/octet-stream", new byte[] {3})))
                .andExpect(status().isCreated());
        assertThat(evidences.count()).isEqualTo(2);
        assertThat(custodyEventCount()).isEqualTo(2);
    }

    @Test
    void cleansStagedAndFinalizedContentWhenPersistenceOrResponseFails() throws Exception {
        CustodyCase persistenceCase = caseWithMembers("Persistence failure", manager, officer);
        doThrow(new DataIntegrityViolationException("forced persistence failure"))
                .when(evidences)
                .saveAndFlush(any(DigitalEvidence.class));
        mockMvc.perform(request(
                        persistenceCase,
                        manager,
                        metadata(metadataJson("FAIL-DB", officer.getId())),
                        file("a.bin", "application/octet-stream", new byte[] {1})))
                .andExpect(status().isInternalServerError());
        assertThat(storedContentCount()).isZero();
        assertThat(evidences.count()).isZero();
        assertThat(custodyEventCount()).isZero();

        org.mockito.Mockito.reset(evidences);
        CustodyCase responseCase = caseWithMembers("Response failure", manager, officer);
        doThrow(new IllegalStateException("forced post-finalize failure"))
                .when(mapper)
                .toResponse(any(DigitalEvidence.class));
        mockMvc.perform(request(
                        responseCase,
                        manager,
                        metadata(metadataJson("FAIL-RESPONSE", officer.getId())),
                        file("b.bin", "application/octet-stream", new byte[] {2})))
                .andExpect(status().isInternalServerError());
        assertThat(evidences.count()).isZero();
        assertThat(custodyEventCount()).isZero();
        assertThat(storedContentCount()).isZero();

        org.mockito.Mockito.reset(mapper);
        CustodyCase eventCase = caseWithMembers("Event failure", manager, officer);
        doThrow(new DataIntegrityViolationException("forced genesis event insert failure"))
                .when(events)
                .saveAndFlush(any(CustodyEvent.class));
        mockMvc.perform(request(
                        eventCase,
                        manager,
                        metadata(metadataJson("FAIL-EVENT", officer.getId())),
                        file("c.bin", "application/octet-stream", new byte[] {3})))
                .andExpect(status().isInternalServerError());
        assertThat(evidences.count()).isZero();
        assertThat(custodyEventCount()).isZero();
        assertThat(storedContentCount()).isZero();

        org.mockito.Mockito.reset(events);
        CustodyCase finalizationCase = caseWithMembers("Finalization failure", manager, officer);
        doThrow(new EvidenceStorageFailureException("forced finalization failure"))
                .when(storage)
                .finalizeStaged(any(StagedEvidence.class));
        mockMvc.perform(request(
                        finalizationCase,
                        manager,
                        metadata(metadataJson("FAIL-FINALIZE", officer.getId())),
                        file("d.bin", "application/octet-stream", new byte[] {4})))
                .andExpect(status().isInternalServerError());
        assertThat(evidences.count()).isZero();
        assertThat(custodyEventCount()).isZero();
        assertThat(storedContentCount()).isZero();
    }

    @Test
    void mapsStorageFailureToStableInternalServerProblem() throws Exception {
        CustodyCase custodyCase = caseWithMembers("Storage failure", manager, officer);
        doThrow(new EvidenceStorageFailureException("forced storage failure"))
                .when(storage)
                .stage(anyString(), any());

        mockMvc.perform(request(
                        custodyCase,
                        manager,
                        metadata(metadataJson("FAIL-STORAGE", officer.getId())),
                        file("e.bin", "application/octet-stream", new byte[] {1})))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/storage-failure"));
        assertThat(evidences.count()).isZero();
        assertThat(custodyEventCount()).isZero();
        assertThat(storedContentCount()).isZero();
    }

    @Test
    void serializesConcurrentSameCaseReferenceTagRegistrations() throws Exception {
        CustodyCase custodyCase = caseWithMembers("Concurrent reference", manager, officer);
        CyclicBarrier bothAuthorized = new CyclicBarrier(2);
        doAnswer(invocation -> {
                    CustodyCase visibleCase = (CustodyCase) invocation.callRealMethod();
                    bothAuthorized.await(10, TimeUnit.SECONDS);
                    return visibleCase;
                })
                .when(access)
                .requireEvidenceRegistrationPermission(any(UUID.class), any());
        var firstRequest = request(
                custodyCase,
                manager,
                metadata(metadataJson(" SAME-TAG ", officer.getId())),
                file("first.bin", "application/octet-stream", new byte[] {1}));
        var secondRequest = request(
                custodyCase,
                manager,
                metadata(metadataJson("same-tag", officer.getId())),
                file("second.bin", "application/octet-stream", new byte[] {2}));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() ->
                    mockMvc.perform(firstRequest).andReturn().getResponse().getStatus());
            Future<Integer> second = executor.submit(() ->
                    mockMvc.perform(secondRequest).andReturn().getResponse().getStatus());

            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(evidences.count()).isEqualTo(1);
        assertThat(custodyEventCount()).isEqualTo(1);
        assertThat(storedContentCount()).isEqualTo(1);
    }

    @Test
    void serializesRegistrationBeforeCompetingCaseClosure() throws Exception {
        CustodyCase custodyCase = caseWithMembers("Concurrent closure", manager, officer);
        CountDownLatch registrationStaged = new CountDownLatch(1);
        CountDownLatch releaseRegistration = new CountDownLatch(1);
        CountDownLatch closureReachedFlush = new CountDownLatch(1);
        doAnswer(invocation -> {
                    StagedEvidence staged = (StagedEvidence) invocation.callRealMethod();
                    registrationStaged.countDown();
                    await(releaseRegistration);
                    return staged;
                })
                .when(storage)
                .stage(anyString(), any());
        doAnswer(invocation -> {
                    closureReachedFlush.countDown();
                    return null;
                })
                .when(custodyCases)
                .flush();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> registration = executor.submit(() -> mockMvc.perform(request(
                            custodyCase,
                            manager,
                            metadata(metadataJson("CLOSE-RACE", officer.getId())),
                            file("e.bin", "application/octet-stream", new byte[] {1})))
                    .andReturn()
                    .getResponse()
                    .getStatus());
            assertThat(registrationStaged.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> closure =
                    executor.submit(() -> mockMvc.perform(patch("/api/v1/cases/{caseId}/status", custodyCase.getId())
                                    .header("Authorization", bearer(manager))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"status\":\"CLOSED\"}"))
                            .andReturn()
                            .getResponse()
                            .getStatus());
            assertThat(closureReachedFlush.await(10, TimeUnit.SECONDS)).isTrue();
            releaseRegistration.countDown();

            assertThat(registration.get(20, TimeUnit.SECONDS)).isEqualTo(201);
            assertThat(closure.get(20, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            releaseRegistration.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(custodyCases.findById(custodyCase.getId()).orElseThrow().getStatus())
                .isEqualTo(CaseStatus.CLOSED);
        assertThat(evidences.count()).isEqualTo(1);
        assertThat(custodyEventCount()).isEqualTo(1);
    }

    @Test
    void documentsMultipartSchemaAndEveryRegistrationResponse() throws Exception {
        String operation = "$.paths['/api/v1/cases/{caseId}/evidences'].post";
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(operation + ".requestBody.content['multipart/form-data'].schema.properties.metadata")
                                .exists())
                .andExpect(jsonPath(operation + ".requestBody.content['multipart/form-data'].schema.properties.file")
                        .exists())
                .andExpect(jsonPath(operation + ".responses['201']").exists())
                .andExpect(jsonPath(operation + ".responses['400']").exists())
                .andExpect(jsonPath(operation + ".responses['401']").exists())
                .andExpect(jsonPath(operation + ".responses['403']").exists())
                .andExpect(jsonPath(operation + ".responses['404']").exists())
                .andExpect(jsonPath(operation + ".responses['409']").exists())
                .andExpect(jsonPath(operation + ".responses['413']").exists())
                .andExpect(jsonPath(operation + ".responses['500']").exists());
    }

    private MockMultipartHttpServletRequestBuilder request(
            CustodyCase custodyCase, Operator actor, MockPart metadata, MockMultipartFile file) throws IOException {
        MockPart filePart = new MockPart("file", file.getOriginalFilename(), file.getBytes());
        filePart.getHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE);
        return (MockMultipartHttpServletRequestBuilder)
                multipart("/api/v1/cases/{caseId}/evidences", custodyCase.getId())
                        .part(metadata)
                        .part(filePart)
                        .file(file)
                        .header("Authorization", bearer(actor));
    }

    private static MockPart metadata(String json) {
        return metadata(json, MediaType.APPLICATION_JSON_VALUE);
    }

    private static MockPart metadata(String json, String mediaType) {
        MockPart metadata = new MockPart("metadata", json.getBytes(StandardCharsets.UTF_8));
        metadata.getHeaders().setContentType(MediaType.parseMediaType(mediaType));
        return metadata;
    }

    private static MockMultipartFile file(String filename, String mediaType, byte[] content) {
        return new MockMultipartFile("file", filename, mediaType, content);
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

    private static String metadataJson(String referenceTag, UUID holderId) {
        return """
                {
                  "referenceTag": "%s",
                  "title": " Disk image ",
                  "description": " Forensic image ",
                  "sourceType": "DEVICE",
                  "sourceDescription": " Workstation ",
                  "sourceManufacturer": " Acme ",
                  "sourceModel": " Model X ",
                  "sourceSerialNumber": " SN-001 ",
                  "sourceLogicalIdentifier": " disk0 ",
                  "acquisitionMethod": "PHYSICAL",
                  "acquiredAt": "2026-01-01T12:00:00Z",
                  "acquisitionLocation": " Evidence room ",
                  "acquisitionToolName": " Imager ",
                  "acquisitionToolVersion": " 1.0 ",
                  "acquisitionNotes": " Write blocker used ",
                  "initialHolderId": "%s"
                }
                """.formatted(referenceTag, holderId);
    }

    private static EvidenceRegisteredPayload registrationPayload(DigitalEvidence evidence) {
        return new EvidenceRegisteredPayload(
                false,
                evidence.getReferenceTag(),
                evidence.getTitle(),
                evidence.getDescription(),
                evidence.getStatus(),
                evidence.getSourceType(),
                evidence.getSourceDescription(),
                evidence.getSourceManufacturer(),
                evidence.getSourceModel(),
                evidence.getSourceSerialNumber(),
                evidence.getSourceLogicalIdentifier(),
                evidence.getAcquisitionMethod(),
                evidence.getAcquiredAt(),
                evidence.getAcquisitionLocation(),
                evidence.getAcquisitionToolName(),
                evidence.getAcquisitionToolVersion(),
                evidence.getAcquisitionNotes(),
                evidence.getOriginalFilename(),
                evidence.getFileExtension(),
                evidence.getMediaType(),
                evidence.getFileSize(),
                evidence.getContentSha256(),
                evidence.getContextualSha256(),
                evidence.getUploadedBy().getId(),
                evidence.getCurrentHolder().getId());
    }

    private static CanonicalCustodyEvent canonicalEvent(
            CustodyEvent event, DigitalEvidence evidence, Operator actor, EvidenceRegisteredPayload payload) {
        return new CanonicalCustodyEvent(
                event.getId(),
                evidence.getCustodyCase().getId(),
                evidence.getId(),
                actor.getId(),
                actor.getRole(),
                event.getSequenceNumber(),
                event.getEventType(),
                event.getOccurredAt(),
                event.getPayloadVersion(),
                payload,
                event.getPreviousHash());
    }

    private byte[] readOnlyStoredContent() throws IOException {
        try (Stream<Path> paths = Files.walk(storageRoot)) {
            Path content = paths.filter(path -> path.getFileName().toString().equals("content.bin"))
                    .findFirst()
                    .orElseThrow();
            return Files.readAllBytes(content);
        }
    }

    private long storedContentCount() throws IOException {
        if (!Files.exists(storageRoot)) {
            return 0;
        }
        try (Stream<Path> paths = Files.walk(storageRoot)) {
            return paths.filter(path -> path.getFileName().toString().equals("content.bin"))
                    .count();
        }
    }

    private long custodyEventCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM custody_events", Long.class);
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

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out while coordinating evidence registration");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating evidence registration", exception);
        }
    }
}
