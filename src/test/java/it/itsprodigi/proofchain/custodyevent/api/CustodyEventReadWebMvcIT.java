package it.itsprodigi.proofchain.custodyevent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.itsprodigi.proofchain.auth.application.JwtTokenService;
import it.itsprodigi.proofchain.custodycase.domain.CaseMembership;
import it.itsprodigi.proofchain.custodycase.domain.CasePriority;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.custodycase.persistence.CustodyCaseRepository;
import it.itsprodigi.proofchain.custodyevent.domain.CustodyEvent;
import it.itsprodigi.proofchain.custodyevent.domain.CustodyEventFactory;
import it.itsprodigi.proofchain.custodyevent.persistence.CustodyEventRepository;
import it.itsprodigi.proofchain.custodyevent.protocol.CanonicalCustodyEvent;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventHashing;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyTransferredPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.EvidenceMetadataSnapshot;
import it.itsprodigi.proofchain.custodyevent.protocol.EvidenceRegisteredPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.EvidenceReleasedPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.EvidenceSealedPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.IntegrityVerifiedPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.MetadataUpdatedPayload;
import it.itsprodigi.proofchain.evidence.domain.AcquisitionMethod;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.domain.EvidenceStatus;
import it.itsprodigi.proofchain.evidence.domain.SourceType;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceRepository;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
class CustodyEventReadWebMvcIT extends PostgreSqlIntegrationTest {

    private static final String BCRYPT_HASH = "$2a$10$01234567890123456789012345678901234567890123456789012";
    private static final Instant BASE_TIME = Instant.parse("2026-07-29T12:34:56.123456Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService tokens;

    @Autowired
    private JsonMapper json;

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
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @PersistenceContext
    private EntityManager entityManager;

    private Operator admin;
    private Operator manager;
    private Operator officer;
    private Operator auditor;
    private Operator outsider;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        admin = saveOperator("event-admin", OperatorRole.ADMIN);
        manager = saveOperator("event-manager", OperatorRole.CASE_MANAGER);
        officer = saveOperator("event-officer", OperatorRole.EVIDENCE_OFFICER);
        auditor = saveOperator("event-auditor", OperatorRole.AUDITOR);
        outsider = saveOperator("event-outsider", OperatorRole.AUDITOR);
    }

    @AfterEach
    void tearDown() {
        ensureProtocolGuards();
        cleanDatabase();
    }

    @Test
    void allowsAdminAndEveryMemberRoleForClosedReleasedEvidenceAndKeepsHistoricalActorRole() throws Exception {
        EventContext context = contextWithAllEvents("READABLE", manager, officer, auditor);
        context.evidence().release();
        evidences.saveAndFlush(context.evidence());
        context.custodyCase().close();
        custodyCases.saveAndFlush(context.custodyCase());
        manager.changeRole(OperatorRole.EVIDENCE_OFFICER);
        operators.saveAndFlush(manager);
        entityManager.clear();

        for (Operator reader : List.of(admin, manager, officer, auditor)) {
            mockMvc.perform(timeline(context.evidence().getId(), reader)).andExpect(status().isOk());
        }

        mockMvc.perform(detail(
                        context.evidence().getId(), context.events().getFirst().getId(), auditor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actorRole").value("CASE_MANAGER"))
                .andExpect(jsonPath("$.eventType").value("EVIDENCE_REGISTERED"));
    }

    @Test
    void hidesEvidenceAndUsesEventNotFoundOnlyAfterVisibilityIsEstablished() throws Exception {
        EventContext visible = contextWithAllEvents("VISIBLE", manager, auditor);
        EventContext other = contextWithAllEvents("OTHER", manager, auditor);

        JsonNode hidden = response(timeline(visible.evidence().getId(), outsider), 404);
        JsonNode missing = response(timeline(UUID.randomUUID(), outsider), 404);
        assertThat(problemIdentity(hidden)).isEqualTo(problemIdentity(missing));
        assertThat(hidden.get("type").asText()).endsWith("/resource-not-found");

        for (UUID eventId : List.of(UUID.randomUUID(), other.events().getFirst().getId())) {
            mockMvc.perform(detail(visible.evidence().getId(), eventId, auditor))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/event-not-found"))
                    .andExpect(jsonPath("$.title").value("Custody event not found"))
                    .andExpect(jsonPath("$.detail").isString());
        }
    }

    @Test
    void pagesInSequenceOrderWithExactBoundedSummariesAndRejectsInvalidQueries() throws Exception {
        EventContext context = contextWithAllEvents("TIMELINE", manager, auditor);
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        sessionFactory.getStatistics().setStatisticsEnabled(true);
        sessionFactory.getStatistics().clear();

        mockMvc.perform(timeline(context.evidence().getId(), auditor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(5)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content", hasSize(6)))
                .andExpect(jsonPath("$.content[0].*", hasSize(12)))
                .andExpect(jsonPath("$.content[0].sequenceNumber").value(1))
                .andExpect(jsonPath("$.content[1].sequenceNumber").value(2))
                .andExpect(jsonPath("$.content[5].sequenceNumber").value(6))
                .andExpect(jsonPath("$.content[0].payload").doesNotExist())
                .andExpect(jsonPath("$.content[0].storageKey").doesNotExist())
                .andExpect(jsonPath("$.content[0].custodyChainHeadHash").doesNotExist())
                .andExpect(jsonPath("$.content[0].operator").doesNotExist());

        assertThat(sessionFactory.getStatistics().getPrepareStatementCount()).isLessThanOrEqualTo(6);

        mockMvc.perform(timeline(context.evidence().getId(), auditor).param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(200));

        for (MockHttpServletRequestBuilder invalid : List.of(
                timeline(context.evidence().getId(), auditor).param("page", "-1"),
                timeline(context.evidence().getId(), auditor).param("size", "0"),
                timeline(context.evidence().getId(), auditor).param("size", "201"),
                timeline(context.evidence().getId(), auditor).param("sort", "sequenceNumber,desc"))) {
            mockMvc.perform(invalid)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/validation-error"));
        }
    }

    @Test
    void returnsEachExactTypedPayloadWithoutEntityOrSecurityLeakage() throws Exception {
        EventContext context = contextWithAllEvents("PAYLOADS", manager, auditor);
        List<CustodyEventPayload> payloads = allPayloads(context.evidence(), manager, officer);

        for (int index = 0; index < payloads.size(); index++) {
            CustodyEvent event = context.events().get(index);
            JsonNode body = response(detail(context.evidence().getId(), event.getId(), auditor), 200);
            assertThat(body.size()).isEqualTo(13);
            assertThat(body.get("eventType").asText())
                    .isEqualTo(payloads.get(index).eventType().name());
            assertThat(body.get("payload")).isEqualTo(reparsed(payloads.get(index)));
            assertThat(body.has("custodyChainHeadHash")).isFalse();
            assertThat(body.has("storageKey")).isFalse();
            assertThat(body.has("passwordHash")).isFalse();
            assertThat(body.has("operator")).isFalse();
        }
    }

    @Test
    void mapsMalformedPayloadAndUnsupportedVersionToSanitizedChainReadFailure() throws Exception {
        EventContext context = contextWithAllEvents("CORRUPT", manager, auditor);
        CustodyEvent event = context.events().getFirst();
        jdbc.execute("ALTER TABLE custody_events DISABLE TRIGGER custody_events_append_only");
        try {
            jdbc.update("UPDATE custody_events SET payload_json = CAST(? AS jsonb) WHERE id = ?", "{}", event.getId());
            assertSanitizedChainReadFailure(context.evidence().getId(), event.getId());

            jdbc.update(
                    "UPDATE custody_events SET payload_json = CAST(? AS jsonb) WHERE id = ?",
                    it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventCanonicalizer.canonicalizePayload(
                            allPayloads(context.evidence(), manager, officer).getFirst()),
                    event.getId());
            jdbc.execute("ALTER TABLE custody_events DROP CONSTRAINT ck_custody_events_payload_version");
            jdbc.update("UPDATE custody_events SET payload_version = 2 WHERE id = ?", event.getId());
            assertSanitizedChainReadFailure(context.evidence().getId(), event.getId());
        } finally {
            ensureProtocolGuards();
        }
    }

    @Test
    void openApiPublishesOnlyTheTwoReadRoutesAndExactPolymorphicSchemas() throws Exception {
        String timelinePath = "/api/v1/evidences/{evidenceId}/events";
        String detailPath = "/api/v1/evidences/{evidenceId}/events/{eventId}";
        String timeline = "$.paths['" + timelinePath + "'].get";
        String detail = "$.paths['" + detailPath + "'].get";
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(timeline + ".operationId").value("listCustodyEvents"))
                .andExpect(jsonPath(detail + ".operationId").value("getCustodyEvent"))
                .andExpect(jsonPath(timeline + ".responses['200']").exists())
                .andExpect(jsonPath(timeline + ".responses['400']").exists())
                .andExpect(jsonPath(timeline + ".responses['401']").exists())
                .andExpect(jsonPath(timeline + ".responses['404']").exists())
                .andExpect(jsonPath(timeline + ".responses['500']").exists())
                .andExpect(jsonPath(detail + ".responses['200']").exists())
                .andExpect(jsonPath(detail + ".responses['400']").exists())
                .andExpect(jsonPath(detail + ".responses['401']").exists())
                .andExpect(jsonPath(detail + ".responses['404']").exists())
                .andExpect(jsonPath(detail + ".responses['500']").exists())
                .andExpect(jsonPath("$.components.schemas.CustodyEventSummaryResponse.properties.*", hasSize(12)))
                .andExpect(jsonPath("$.components.schemas.CustodyEventDetailResponse.properties.*", hasSize(13)))
                .andExpect(jsonPath("$.components.schemas.CustodyEventPageResponse.properties.*", hasSize(5)))
                .andExpect(jsonPath("$.components.schemas.CustodyEventPayload.oneOf", hasSize(6)))
                .andReturn();

        JsonNode paths =
                json.readTree(result.getResponse().getContentAsString()).get("paths");
        assertThat(paths.propertyNames())
                .filteredOn(path -> path.contains("/events"))
                .containsExactlyInAnyOrder(timelinePath, detailPath);
        assertThat(paths.get(timelinePath).propertyNames()).containsExactly("get");
        assertThat(paths.get(detailPath).propertyNames()).containsExactly("get");
    }

    private EventContext contextWithAllEvents(String referenceTag, Operator creator, Operator... members) {
        CustodyCase custodyCase = custodyCases.saveAndFlush(
                CustodyCase.create("Event case " + referenceTag, null, null, null, null, CasePriority.HIGH, creator));
        memberships.save(CaseMembership.assign(custodyCase, creator, creator));
        for (Operator member : members) {
            memberships.save(CaseMembership.assign(custodyCase, member, creator));
        }
        memberships.flush();
        UUID evidenceId = UUID.randomUUID();
        DigitalEvidence evidence = evidences.saveAndFlush(DigitalEvidence.create(
                evidenceId,
                custodyCase,
                officer,
                creator,
                referenceTag,
                "Forensic disk image",
                "Complete public snapshot",
                SourceType.DEVICE,
                "Workstation SSD",
                "Acme",
                "Forensic One",
                "SN-01",
                "/dev/nvme0n1",
                AcquisitionMethod.PHYSICAL,
                "Evidence room",
                "Forensic Imager",
                "1.0",
                "Write blocker used",
                Instant.parse("2026-07-29T11:30:00.654321Z"),
                "disk-image.E01",
                MediaType.APPLICATION_OCTET_STREAM_VALUE,
                4096,
                "b".repeat(64),
                "c".repeat(64),
                "cases/" + custodyCase.getId() + "/evidences/" + evidenceId + "/content.bin",
                BASE_TIME));

        List<CustodyEvent> stored = new ArrayList<>();
        String previousHash = CustodyEventHashing.ZERO_HASH;
        List<CustodyEventPayload> payloads = allPayloads(evidence, creator, officer);
        for (int index = 0; index < payloads.size(); index++) {
            long sequence = index + 1L;
            Instant misleadingTime = index < 2 ? BASE_TIME.plusSeconds(6) : BASE_TIME.plusSeconds(6L - index);
            CanonicalCustodyEvent canonical = new CanonicalCustodyEvent(
                    UUID.randomUUID(),
                    custodyCase.getId(),
                    evidence.getId(),
                    creator.getId(),
                    creator.getRole(),
                    sequence,
                    payloads.get(index).eventType(),
                    misleadingTime,
                    CanonicalCustodyEvent.PAYLOAD_VERSION,
                    payloads.get(index),
                    previousHash);
            String eventHash = CustodyEventHashing.eventHash(canonical);
            stored.add(events.saveAndFlush(
                    CustodyEventFactory.create(canonical, custodyCase, evidence, creator, eventHash)));
            previousHash = eventHash;
        }
        entityManager.clear();
        return new EventContext(custodyCase, evidence, List.copyOf(stored));
    }

    private static List<CustodyEventPayload> allPayloads(
            DigitalEvidence evidence, Operator uploader, Operator initialHolder) {
        EvidenceMetadataSnapshot before = new EvidenceMetadataSnapshot(
                evidence.getTitle(),
                evidence.getDescription(),
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
                evidence.getAcquisitionNotes());
        EvidenceMetadataSnapshot after = new EvidenceMetadataSnapshot(
                "Updated forensic disk image",
                evidence.getDescription(),
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
                evidence.getAcquisitionNotes());
        return List.of(
                new EvidenceRegisteredPayload(
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
                        uploader.getId(),
                        initialHolder.getId()),
                new CustodyTransferredPayload(initialHolder.getId(), uploader.getId(), "Transfer for examination"),
                new MetadataUpdatedPayload(before, after, "Corrected title"),
                new IntegrityVerifiedPayload("SHA-256", "b".repeat(64), "b".repeat(64), true, 4096),
                new EvidenceSealedPayload(
                        EvidenceStatus.IN_CUSTODY, EvidenceStatus.SEALED, uploader.getId(), "Examination complete"),
                new EvidenceReleasedPayload(
                        EvidenceStatus.SEALED,
                        EvidenceStatus.RELEASED,
                        uploader.getId(),
                        null,
                        "Released to authority"));
    }

    private void assertSanitizedChainReadFailure(UUID evidenceId, UUID eventId) throws Exception {
        mockMvc.perform(detail(evidenceId, eventId, auditor))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/custody-chain-read-failure"))
                .andExpect(jsonPath("$.title").value("Custody chain read failure"))
                .andExpect(jsonPath("$.detail").isString())
                .andExpect(jsonPath("$.detail").value("Custody chain data could not be read safely."));
    }

    /**
     * Serializes then reparses the payload so numeric nodes carry the same concrete JsonNode type as the
     * parsed HTTP response. Direct valueToTree keeps a {@code long} as a LongNode, which never equals the
     * IntNode produced by parsing the identical JSON text.
     */
    private JsonNode reparsed(CustodyEventPayload payload) {
        return json.readTree(json.writeValueAsString(payload));
    }

    private JsonNode response(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        MvcResult result =
                mockMvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static List<String> problemIdentity(JsonNode problem) {
        return List.of(
                problem.get("type").asText(),
                problem.get("title").asText(),
                problem.get("status").asText(),
                problem.get("detail").asText());
    }

    private MockHttpServletRequestBuilder timeline(UUID evidenceId, Operator reader) {
        return get("/api/v1/evidences/{evidenceId}/events", evidenceId)
                .header(HttpHeaders.AUTHORIZATION, bearer(reader));
    }

    private MockHttpServletRequestBuilder detail(UUID evidenceId, UUID eventId, Operator reader) {
        return get("/api/v1/evidences/{evidenceId}/events/{eventId}", evidenceId, eventId)
                .header(HttpHeaders.AUTHORIZATION, bearer(reader));
    }

    private String bearer(Operator operator) {
        return "Bearer "
                + tokens.issue(operator.getId(), operator.getUsername(), operator.getRole())
                        .value();
    }

    private Operator saveOperator(String username, OperatorRole role) {
        return operators.saveAndFlush(
                Operator.create(username, username + "@example.com", BCRYPT_HASH, "First", "Last", role));
    }

    private void ensureProtocolGuards() {
        Boolean constraintExists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_custody_events_payload_version')",
                Boolean.class);
        if (!Boolean.TRUE.equals(constraintExists)) {
            jdbc.update("UPDATE custody_events SET payload_version = 1 WHERE payload_version <> 1");
            jdbc.execute(
                    "ALTER TABLE custody_events ADD CONSTRAINT ck_custody_events_payload_version CHECK (payload_version = 1)");
        }
        jdbc.execute("ALTER TABLE custody_events ENABLE TRIGGER custody_events_append_only");
    }

    private void cleanDatabase() {
        jdbc.execute("TRUNCATE TABLE custody_events");
        evidences.deleteAllInBatch();
        memberships.deleteAllInBatch();
        custodyCases.deleteAllInBatch();
        operators.deleteAllInBatch();
    }

    private record EventContext(CustodyCase custodyCase, DigitalEvidence evidence, List<CustodyEvent> events) {}
}
