package it.itsprodigi.proofchain.custodyevent.api;

import static org.assertj.core.api.Assertions.assertThat;
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
import it.itsprodigi.proofchain.custodyevent.application.CustodyEventAppender;
import it.itsprodigi.proofchain.custodyevent.domain.EventType;
import it.itsprodigi.proofchain.custodyevent.persistence.CustodyEventRepository;
import it.itsprodigi.proofchain.custodyevent.protocol.CanonicalCustodyEvent;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventCanonicalizer;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventHashing;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventPayload;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyTransferredPayload;
import it.itsprodigi.proofchain.evidence.domain.AcquisitionMethod;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.domain.SourceType;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceRepository;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integration coverage for {@code POST /api/v1/evidences/{evidenceId}/verify-chain}. Deliberately never
 * disables the {@code custody_events_append_only} trigger: schema-reachable corruptions are produced by
 * inserting purpose-built rows on a dedicated evidence, or by setting the evidence anchor columns, never by
 * mutating a previously valid event row.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustodyChainVerificationWebMvcIT extends PostgreSqlIntegrationTest {

    private static final String BCRYPT_HASH = "$2a$10$01234567890123456789012345678901234567890123456789012";
    private static final Instant BASE_TIME = Instant.parse("2026-07-29T12:34:56.123456Z");
    private static final String VERIFY_PATH = "/api/v1/evidences/{evidenceId}/verify-chain";

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
    private CustodyEventAppender appender;

    @Autowired
    private TransactionTemplate transactionTemplate;

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
    void setUp() {
        cleanDatabase();
        admin = saveOperator("chain-admin", OperatorRole.ADMIN);
        manager = saveOperator("chain-manager", OperatorRole.CASE_MANAGER);
        officer = saveOperator("chain-officer", OperatorRole.EVIDENCE_OFFICER);
        auditor = saveOperator("chain-auditor", OperatorRole.AUDITOR);
        outsider = saveOperator("chain-outsider", OperatorRole.AUDITOR);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void validChainReturnsTheExactValidInvariantForAdminAndEveryMemberRoleIncludingAuditor() throws Exception {
        ChainContext context = buildValidChain("VALID", 4, manager, officer, auditor);

        for (Operator reader : java.util.List.of(admin, manager, officer, auditor)) {
            JsonNode body = response(verify(context.evidence().getId(), reader), 200);
            assertThat(body.get("evidenceId").asText())
                    .isEqualTo(context.evidence().getId().toString());
            assertThat(body.get("valid").asBoolean()).isTrue();
            assertThat(body.get("checkedEvents").asLong()).isEqualTo(4);
            assertThat(body.get("storedEventCount").asLong()).isEqualTo(4);
            assertThat(body.get("loadedEventCount").asLong()).isEqualTo(4);
            assertThat(body.get("calculatedHeadHash").asText())
                    .isEqualTo(body.get("storedHeadHash").asText());
            assertThat(body.get("reason").isNull()).isTrue();
            assertThat(body.get("brokenAtEventId").isNull()).isTrue();
            assertThat(body.get("brokenAtSequenceNumber").isNull()).isTrue();
            assertThat(body.get("expectedValue").isNull()).isTrue();
            assertThat(body.get("actualValue").isNull()).isTrue();
            assertThat(body.get("verifiedAt").asText()).isNotBlank();
        }

        assertNoMutation(context.evidence().getId(), 4, 4);
    }

    @Test
    void closedCaseAndReleasedEvidenceRemainVerifiable() throws Exception {
        ChainContext context = buildValidChain("CLOSED", 2, manager, auditor);
        DigitalEvidence evidence = context.evidence();
        evidence.release();
        evidences.saveAndFlush(evidence);
        CustodyCase custodyCase = context.custodyCase();
        custodyCase.close();
        custodyCases.saveAndFlush(custodyCase);
        entityManager.clear();

        mockMvc.perform(verify(context.evidence().getId(), auditor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.checkedEvents").value(2));
    }

    @Test
    void hiddenEvidenceUsesTheCertifiedNotFoundBoundary() throws Exception {
        ChainContext visible = buildValidChain("HIDDEN", 1, manager, auditor);

        JsonNode hidden = response(verify(visible.evidence().getId(), outsider), 404);
        JsonNode missing = response(verify(UUID.randomUUID(), outsider), 404);

        assertThat(problemIdentity(hidden)).isEqualTo(problemIdentity(missing));
        assertThat(hidden.get("type").asText()).endsWith("/resource-not-found");
    }

    @Test
    void emptyChainIsReportedAsAValidatedDiagnosticNotAServerError() throws Exception {
        CorruptionFixture fixture = dedicatedEvidence("EMPTY");

        JsonNode body = response(verify(fixture.evidence().getId(), auditor), 200);

        assertThat(body.get("valid").asBoolean()).isFalse();
        assertThat(body.get("reason").asText()).isEqualTo("EMPTY_CHAIN");
        assertThat(body.get("checkedEvents").asLong()).isZero();
        assertThat(body.get("storedEventCount").asLong()).isZero();
        assertThat(body.get("loadedEventCount").asLong()).isZero();
        assertThat(body.get("brokenAtEventId").isNull()).isTrue();
        assertNoMutation(fixture.evidence().getId(), 0, 0);
    }

    @Test
    void chainLengthMismatchIsDetectedViaTheEvidenceAnchor() throws Exception {
        CorruptionFixture fixture = dedicatedEvidence("LENGTH");
        ComputedEvent genesis = computeGenesisEvent(fixture);
        insertRawEvent(fixture, genesis, 1, CustodyEventHashing.ZERO_HASH);
        advanceAnchor(fixture.evidence().getId(), 1, genesis.eventHash());
        jdbc.update(
                "UPDATE digital_evidence SET custody_event_count = 2 WHERE id = ?",
                fixture.evidence().getId());
        entityManager.clear();

        JsonNode body = response(verify(fixture.evidence().getId(), auditor), 200);

        assertThat(body.get("valid").asBoolean()).isFalse();
        assertThat(body.get("reason").asText()).isEqualTo("CHAIN_LENGTH_MISMATCH");
        assertThat(body.get("storedEventCount").asLong()).isEqualTo(2);
        assertThat(body.get("loadedEventCount").asLong()).isEqualTo(1);
        assertThat(body.get("expectedValue").asText()).isEqualTo("2");
        assertThat(body.get("actualValue").asText()).isEqualTo("1");
        assertNoMutation(fixture.evidence().getId(), 2, 1, genesis.eventHash());
    }

    @Test
    void genesisMismatchIsDetectedWhenTheFirstEventsPreviousHashIsNotZero() throws Exception {
        CorruptionFixture fixture = dedicatedEvidence("GENESIS");
        String wrongPreviousHash = "a".repeat(64);
        ComputedEvent genesis = computeEvent(fixture, 1, wrongPreviousHash);
        insertRawEvent(fixture, genesis, 1, wrongPreviousHash);
        advanceAnchor(fixture.evidence().getId(), 1, genesis.eventHash());
        entityManager.clear();

        JsonNode body = response(verify(fixture.evidence().getId(), auditor), 200);

        assertThat(body.get("valid").asBoolean()).isFalse();
        assertThat(body.get("reason").asText()).isEqualTo("GENESIS_MISMATCH");
        assertThat(body.get("brokenAtEventId").asText())
                .isEqualTo(genesis.eventId().toString());
        assertThat(body.get("brokenAtSequenceNumber").asLong()).isEqualTo(1);
        assertThat(body.get("expectedValue").asText()).isEqualTo(CustodyEventHashing.ZERO_HASH);
        assertThat(body.get("actualValue").asText()).isEqualTo(wrongPreviousHash);
        assertNoMutation(fixture.evidence().getId(), 1, 1, genesis.eventHash());
    }

    @Test
    void sequenceGapIsDetectedWhenTheSecondEventSkipsAnOrdinal() throws Exception {
        CorruptionFixture fixture = dedicatedEvidence("SEQGAP");
        ComputedEvent genesis = computeGenesisEvent(fixture);
        insertRawEvent(fixture, genesis, 1, CustodyEventHashing.ZERO_HASH);
        ComputedEvent skipped = computeEvent(fixture, 3, genesis.eventHash());
        insertRawEvent(fixture, skipped, 3, genesis.eventHash());
        advanceAnchor(fixture.evidence().getId(), 1, genesis.eventHash());
        advanceAnchor(fixture.evidence().getId(), 2, skipped.eventHash());
        entityManager.clear();

        JsonNode body = response(verify(fixture.evidence().getId(), auditor), 200);

        assertThat(body.get("valid").asBoolean()).isFalse();
        assertThat(body.get("reason").asText()).isEqualTo("SEQUENCE_GAP");
        assertThat(body.get("checkedEvents").asLong()).isEqualTo(1);
        assertThat(body.get("calculatedHeadHash").asText()).isEqualTo(genesis.eventHash());
        assertThat(body.get("brokenAtEventId").asText())
                .isEqualTo(skipped.eventId().toString());
        assertThat(body.get("expectedValue").asText()).isEqualTo("2");
        assertThat(body.get("actualValue").asText()).isEqualTo("3");
        assertNoMutation(fixture.evidence().getId(), 2, 2, skipped.eventHash());
    }

    @Test
    void previousHashMismatchIsDetectedWhenTheLinkDoesNotMatchThePriorEventHash() throws Exception {
        CorruptionFixture fixture = dedicatedEvidence("PREVHASH");
        ComputedEvent genesis = computeGenesisEvent(fixture);
        insertRawEvent(fixture, genesis, 1, CustodyEventHashing.ZERO_HASH);
        String wrongPreviousHash = "b".repeat(64);
        ComputedEvent second = computeEvent(fixture, 2, wrongPreviousHash);
        insertRawEvent(fixture, second, 2, wrongPreviousHash);
        advanceAnchor(fixture.evidence().getId(), 1, genesis.eventHash());
        advanceAnchor(fixture.evidence().getId(), 2, second.eventHash());
        entityManager.clear();

        JsonNode body = response(verify(fixture.evidence().getId(), auditor), 200);

        assertThat(body.get("valid").asBoolean()).isFalse();
        assertThat(body.get("reason").asText()).isEqualTo("PREVIOUS_HASH_MISMATCH");
        assertThat(body.get("checkedEvents").asLong()).isEqualTo(1);
        assertThat(body.get("brokenAtEventId").asText())
                .isEqualTo(second.eventId().toString());
        assertThat(body.get("expectedValue").asText()).isEqualTo(genesis.eventHash());
        assertThat(body.get("actualValue").asText()).isEqualTo(wrongPreviousHash);
        assertNoMutation(fixture.evidence().getId(), 2, 2, second.eventHash());
    }

    @Test
    void eventHashMismatchIsDetectedWhenTheStoredHashDoesNotMatchTheRecomputedHash() throws Exception {
        CorruptionFixture fixture = dedicatedEvidence("EVENTHASH");
        ComputedEvent genesis = computeGenesisEvent(fixture);
        String wrongEventHash = flippedHash(genesis.eventHash());
        insertRawEvent(fixture, genesis, wrongEventHash, 1, CustodyEventHashing.ZERO_HASH);
        advanceAnchor(fixture.evidence().getId(), 1, wrongEventHash);
        entityManager.clear();

        JsonNode body = response(verify(fixture.evidence().getId(), auditor), 200);

        assertThat(body.get("valid").asBoolean()).isFalse();
        assertThat(body.get("reason").asText()).isEqualTo("EVENT_HASH_MISMATCH");
        assertThat(body.get("brokenAtEventId").asText())
                .isEqualTo(genesis.eventId().toString());
        assertThat(body.get("expectedValue").asText()).isEqualTo(genesis.eventHash());
        assertThat(body.get("actualValue").asText()).isEqualTo(wrongEventHash);
        assertNoMutation(fixture.evidence().getId(), 1, 1, wrongEventHash);
    }

    @Test
    void chainHeadMismatchIsDetectedWhenTheEvidenceAnchorDisagreesWithTheLastEventHash() throws Exception {
        CorruptionFixture fixture = dedicatedEvidence("HEADHASH");
        ComputedEvent genesis = computeGenesisEvent(fixture);
        insertRawEvent(fixture, genesis, 1, CustodyEventHashing.ZERO_HASH);
        String wrongHeadHash = flippedHash(genesis.eventHash());
        advanceAnchor(fixture.evidence().getId(), 1, wrongHeadHash);
        entityManager.clear();

        JsonNode body = response(verify(fixture.evidence().getId(), auditor), 200);

        assertThat(body.get("valid").asBoolean()).isFalse();
        assertThat(body.get("reason").asText()).isEqualTo("CHAIN_HEAD_MISMATCH");
        assertThat(body.get("checkedEvents").asLong()).isEqualTo(1);
        assertThat(body.get("brokenAtEventId").isNull()).isTrue();
        assertThat(body.get("expectedValue").asText()).isEqualTo(wrongHeadHash);
        assertThat(body.get("actualValue").asText()).isEqualTo(genesis.eventHash());
        assertNoMutation(fixture.evidence().getId(), 1, 1, wrongHeadHash);
    }

    @Test
    void invalidPayloadIsReportedAsAValidatedDiagnosticNotAServerError() throws Exception {
        CorruptionFixture fixture = dedicatedEvidence("BADPAYLOAD");
        ComputedEvent genesis = computeGenesisEvent(fixture);
        insertRawEvent(
                fixture.evidence().getId(),
                fixture.custodyCase().getId(),
                fixture.actor().getId(),
                fixture.actor().getRole(),
                genesis.eventId(),
                1,
                genesis.eventType(),
                genesis.occurredAt(),
                "{}",
                CustodyEventHashing.ZERO_HASH,
                genesis.eventHash());
        advanceAnchor(fixture.evidence().getId(), 1, genesis.eventHash());
        entityManager.clear();

        JsonNode body = response(verify(fixture.evidence().getId(), auditor), 200);

        assertThat(body.get("valid").asBoolean()).isFalse();
        assertThat(body.get("reason").asText()).isEqualTo("INVALID_PAYLOAD");
        assertThat(body.get("expectedValue").asText()).isEqualTo(EventType.CUSTODY_TRANSFERRED.name());
        assertThat(body.get("actualValue").asText()).isEqualTo("invalid payload");
        assertNoMutation(fixture.evidence().getId(), 1, 1, genesis.eventHash());
    }

    @Test
    void openApiPublishesVerifyChainWithTheExactPathAndNoAlias() throws Exception {
        String responses = "$.paths['" + VERIFY_PATH + "'].post.responses";
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + VERIFY_PATH + "'].post.operationId")
                        .value("verifyCustodyChain"))
                .andExpect(jsonPath("$.paths['" + VERIFY_PATH + "'].get").doesNotExist())
                .andExpect(jsonPath(responses + "['200']").exists())
                .andExpect(jsonPath(responses + "['400']").exists())
                .andExpect(jsonPath(responses + "['401']").exists())
                .andExpect(jsonPath(responses + "['404']").exists())
                .andExpect(jsonPath(responses + "['500']").exists())
                .andExpect(jsonPath(
                        "$.components.schemas.CustodyChainVerificationResponse.properties.*",
                        org.hamcrest.Matchers.hasSize(13)))
                .andReturn();

        JsonNode paths =
                json.readTree(result.getResponse().getContentAsString()).get("paths");
        assertThat(paths.propertyNames())
                .filteredOn(path -> path.contains("verify-chain"))
                .containsExactly(VERIFY_PATH);
        assertThat(paths.get(VERIFY_PATH).propertyNames()).containsExactly("post");
    }

    // ------------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------------

    private ChainContext buildValidChain(String referenceTag, int eventCount, Operator creator, Operator... members) {
        CustodyCase custodyCase = custodyCases.saveAndFlush(
                CustodyCase.create("Chain case " + referenceTag, null, null, null, null, CasePriority.HIGH, creator));
        memberships.save(CaseMembership.assign(custodyCase, creator, creator));
        for (Operator member : members) {
            memberships.save(CaseMembership.assign(custodyCase, member, creator));
        }
        memberships.flush();
        UUID evidenceId = UUID.randomUUID();
        DigitalEvidence evidence = evidences.saveAndFlush(newEvidence(custodyCase, creator, referenceTag, evidenceId));

        for (int index = 0; index < eventCount; index++) {
            CustodyEventPayload payload = transferPayload(index + 1L);
            transactionTemplate.executeWithoutResult(status -> appender.append(evidenceId, creator, payload));
        }
        entityManager.clear();
        return new ChainContext(
                custodyCases.findById(custodyCase.getId()).orElseThrow(),
                evidences.findById(evidenceId).orElseThrow());
    }

    private CorruptionFixture dedicatedEvidence(String referenceTag) {
        CustodyCase custodyCase = custodyCases.saveAndFlush(
                CustodyCase.create("Corrupt case " + referenceTag, null, null, null, null, CasePriority.HIGH, manager));
        memberships.save(CaseMembership.assign(custodyCase, manager, manager));
        memberships.save(CaseMembership.assign(custodyCase, auditor, manager));
        memberships.flush();
        UUID evidenceId = UUID.randomUUID();
        DigitalEvidence evidence = evidences.saveAndFlush(newEvidence(custodyCase, manager, referenceTag, evidenceId));
        entityManager.clear();
        return new CorruptionFixture(
                custodyCases.findById(custodyCase.getId()).orElseThrow(),
                evidences.findById(evidenceId).orElseThrow(),
                manager);
    }

    private DigitalEvidence newEvidence(
            CustodyCase custodyCase, Operator creator, String referenceTag, UUID evidenceId) {
        return DigitalEvidence.create(
                evidenceId,
                custodyCase,
                officer,
                creator,
                referenceTag,
                "Forensic disk image",
                "Chain verification fixture",
                SourceType.DEVICE,
                null,
                null,
                null,
                null,
                null,
                AcquisitionMethod.PHYSICAL,
                null,
                null,
                null,
                null,
                Instant.parse("2026-07-29T11:30:00.654321Z"),
                "disk-image.E01",
                "application/octet-stream",
                4096,
                "b".repeat(64),
                "c".repeat(64),
                "cases/" + custodyCase.getId() + "/evidences/" + evidenceId + "/content.bin",
                BASE_TIME);
    }

    private static CustodyEventPayload transferPayload(long sequenceNumber) {
        return sequenceNumber % 2 == 1
                ? new CustodyTransferredPayload(
                        UUID.fromString("c0000000-0000-4000-8000-000000000001"),
                        UUID.fromString("c0000000-0000-4000-8000-000000000002"),
                        "Transfer " + sequenceNumber)
                : new CustodyTransferredPayload(
                        UUID.fromString("c0000000-0000-4000-8000-000000000002"),
                        UUID.fromString("c0000000-0000-4000-8000-000000000001"),
                        "Transfer " + sequenceNumber);
    }

    private ComputedEvent computeGenesisEvent(CorruptionFixture fixture) {
        return computeEvent(fixture, 1, CustodyEventHashing.ZERO_HASH);
    }

    private ComputedEvent computeEvent(CorruptionFixture fixture, long sequenceNumber, String previousHash) {
        UUID eventId = UUID.randomUUID();
        CustodyEventPayload payload = transferPayload(sequenceNumber);
        Instant occurredAt = BASE_TIME.plusSeconds(sequenceNumber);
        CanonicalCustodyEvent canonical = new CanonicalCustodyEvent(
                eventId,
                fixture.custodyCase().getId(),
                fixture.evidence().getId(),
                fixture.actor().getId(),
                fixture.actor().getRole(),
                sequenceNumber,
                payload.eventType(),
                occurredAt,
                CanonicalCustodyEvent.PAYLOAD_VERSION,
                payload,
                previousHash);
        String eventHash = CustodyEventHashing.eventHash(canonical);
        return new ComputedEvent(
                eventId,
                payload.eventType(),
                occurredAt,
                CustodyEventCanonicalizer.canonicalizePayload(payload),
                eventHash);
    }

    private void insertRawEvent(
            CorruptionFixture fixture, ComputedEvent event, long sequenceNumber, String previousHash) {
        insertRawEvent(fixture, event, event.eventHash(), sequenceNumber, previousHash);
    }

    private void insertRawEvent(
            CorruptionFixture fixture,
            ComputedEvent event,
            String storedEventHash,
            long sequenceNumber,
            String previousHash) {
        insertRawEvent(
                fixture.evidence().getId(),
                fixture.custodyCase().getId(),
                fixture.actor().getId(),
                fixture.actor().getRole(),
                event.eventId(),
                sequenceNumber,
                event.eventType(),
                event.occurredAt(),
                event.payloadJson(),
                previousHash,
                storedEventHash);
    }

    private void insertRawEvent(
            UUID evidenceId,
            UUID caseId,
            UUID operatorId,
            OperatorRole actorRole,
            UUID eventId,
            long sequenceNumber,
            EventType eventType,
            Instant occurredAt,
            String payloadJson,
            String previousHash,
            String eventHash) {
        jdbc.update(
                """
                INSERT INTO custody_events
                    (id, case_id, evidence_id, operator_id, actor_role, sequence_number, event_type, occurred_at,
                     payload_version, payload_json, previous_hash, event_hash, hash_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, CAST(? AS jsonb), ?, ?, 1)
                """,
                eventId,
                caseId,
                evidenceId,
                operatorId,
                actorRole.name(),
                sequenceNumber,
                eventType.name(),
                Timestamp.from(occurredAt),
                payloadJson,
                previousHash,
                eventHash);
    }

    private void advanceAnchor(UUID evidenceId, long sequenceNumber, String eventHash) {
        DigitalEvidence evidence = evidences.findById(evidenceId).orElseThrow();
        evidence.advanceCustodyChain(sequenceNumber, eventHash);
        evidences.saveAndFlush(evidence);
        entityManager.clear();
    }

    private static String flippedHash(String hash) {
        char first = hash.charAt(0);
        char replacement = first == '0' ? '1' : '0';
        return replacement + hash.substring(1);
    }

    private void assertNoMutation(UUID evidenceId, long expectedCount, long expectedEventRows) {
        DigitalEvidence reloaded = evidences.findById(evidenceId).orElseThrow();
        assertNoMutation(evidenceId, expectedCount, expectedEventRows, reloaded.getCustodyChainHeadHash());
    }

    private void assertNoMutation(
            UUID evidenceId, long expectedCount, long expectedEventRows, String expectedHeadHash) {
        DigitalEvidence reloaded = evidences.findById(evidenceId).orElseThrow();
        assertThat(reloaded.getCustodyEventCount()).isEqualTo(expectedCount);
        assertThat(reloaded.getCustodyChainHeadHash()).isEqualTo(expectedHeadHash);
        assertThat(events.countByEvidenceId(evidenceId)).isEqualTo(expectedEventRows);
    }

    private JsonNode response(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        MvcResult result =
                mockMvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static java.util.List<String> problemIdentity(JsonNode problem) {
        return java.util.List.of(
                problem.get("type").asText(),
                problem.get("title").asText(),
                problem.get("status").asText(),
                problem.get("detail").asText());
    }

    private MockHttpServletRequestBuilder verify(UUID evidenceId, Operator reader) {
        return post(VERIFY_PATH, evidenceId).header(HttpHeaders.AUTHORIZATION, bearer(reader));
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

    private void cleanDatabase() {
        jdbc.execute("TRUNCATE TABLE custody_events");
        evidences.deleteAllInBatch();
        memberships.deleteAllInBatch();
        custodyCases.deleteAllInBatch();
        operators.deleteAllInBatch();
    }

    private record ChainContext(CustodyCase custodyCase, DigitalEvidence evidence) {}

    private record CorruptionFixture(CustodyCase custodyCase, DigitalEvidence evidence, Operator actor) {}

    private record ComputedEvent(
            UUID eventId, EventType eventType, Instant occurredAt, String payloadJson, String eventHash) {}
}
