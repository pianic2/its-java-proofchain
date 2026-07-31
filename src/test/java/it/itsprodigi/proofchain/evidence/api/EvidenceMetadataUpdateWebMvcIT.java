package it.itsprodigi.proofchain.evidence.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.itsprodigi.proofchain.auth.application.JwtTokenService;
import it.itsprodigi.proofchain.custodycase.domain.CaseMembership;
import it.itsprodigi.proofchain.custodycase.domain.CasePriority;
import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import it.itsprodigi.proofchain.custodycase.persistence.CaseMembershipRepository;
import it.itsprodigi.proofchain.custodycase.persistence.CustodyCaseRepository;
import it.itsprodigi.proofchain.custodyevent.persistence.CustodyEventRepository;
import it.itsprodigi.proofchain.custodyevent.protocol.CustodyEventHashing;
import it.itsprodigi.proofchain.evidence.domain.AcquisitionMethod;
import it.itsprodigi.proofchain.evidence.domain.DigitalEvidence;
import it.itsprodigi.proofchain.evidence.domain.SourceType;
import it.itsprodigi.proofchain.evidence.persistence.DigitalEvidenceRepository;
import it.itsprodigi.proofchain.operator.domain.Operator;
import it.itsprodigi.proofchain.operator.domain.OperatorRole;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import it.itsprodigi.proofchain.support.PostgreSqlIntegrationTest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP contract of the canonical descriptive metadata endpoint.
 *
 * <p>The presence-aware semantics, the normalization boundaries, the strict rejection of unknown and immutable
 * properties, the no-op conflict, the lifecycle gates and the documented OpenAPI operation are all proven through the
 * real endpoint against PostgreSQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EvidenceMetadataUpdateWebMvcIT extends PostgreSqlIntegrationTest {

    private static final String METADATA_PATH = "/api/v1/evidences/{evidenceId}/metadata";
    private static final String VALIDATION_ERROR = "https://proofchain.dev/problems/validation-error";
    private static final String REASON = "Corrected the acquisition metadata after the laboratory review.";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String TITLE = "Forensic disk image of the seized laptop";
    private static final String DESCRIPTION = "Full physical acquisition of the internal drive.";
    private static final SourceType SOURCE_TYPE = SourceType.DEVICE;
    private static final String SOURCE_DESCRIPTION = "Laptop seized in the living room.";
    private static final String SOURCE_MANUFACTURER = "ACME";
    private static final String SOURCE_MODEL = "X1-2026";
    private static final String SOURCE_SERIAL_NUMBER = "SN-0042-AB";
    private static final String SOURCE_LOGICAL_IDENTIFIER = "host-42/volume-1";
    private static final AcquisitionMethod ACQUISITION_METHOD = AcquisitionMethod.PHYSICAL;
    private static final Instant ACQUIRED_AT = Instant.parse("2020-01-01T00:00:00Z");
    private static final String ACQUISITION_LOCATION = "Evidence room B";
    private static final String ACQUISITION_TOOL_NAME = "AcquireTool";
    private static final String ACQUISITION_TOOL_VERSION = "3.1.4";
    private static final String ACQUISITION_NOTES = "Write blocker used during acquisition.";

    private final AtomicInteger referenceTags = new AtomicInteger();

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
    private CustodyEventRepository events;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Operator manager;
    private Operator officer;
    private Operator auditor;
    private Operator outsider;
    private CustodyCase owningCase;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        manager = saveOperator("mvc-metadata-manager", OperatorRole.CASE_MANAGER);
        officer = saveOperator("mvc-metadata-officer", OperatorRole.EVIDENCE_OFFICER);
        auditor = saveOperator("mvc-metadata-auditor", OperatorRole.AUDITOR);
        outsider = saveOperator("mvc-metadata-outsider", OperatorRole.CASE_MANAGER);
        owningCase = custodyCases.saveAndFlush(
                CustodyCase.create("Metadata HTTP case", null, null, null, null, CasePriority.HIGH, manager));
        assign(manager, officer, auditor);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    /**
     * The heart of the contract, proven once for every modifiable field: an absent property preserves the current
     * value, an explicit null clears an optional one or is rejected for a required one, and blank optional text is
     * trimmed to null. Every assertion also compares the other thirteen fields, so a patch can never leak sideways.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Patchable.class)
    void separatesAbsentFromExplicitNullFromBlankForEveryModifiableField(Patchable field) throws Exception {
        DigitalEvidence changed = freshEvidence();
        Map<String, Object> baseline = metadataOf(changed);

        perform(changed, manager, quoted(field.property, field.newJsonValue)).andExpect(status().isOk());

        Map<String, Object> expected = new LinkedHashMap<>(baseline);
        expected.put(field.property, field.newValue);
        assertThat(metadataOf(reload(changed))).isEqualTo(expected);
        assertThat(events.countByEvidenceId(changed.getId())).isEqualTo(1L);

        DigitalEvidence nulled = freshEvidence();
        ResultActions explicitNull =
                perform(nulled, manager, "{\"%s\":null,\"reason\":\"%s\"}".formatted(field.property, REASON));
        if (field.nullClears) {
            explicitNull.andExpect(status().isOk());
            Map<String, Object> cleared = new LinkedHashMap<>(baseline);
            cleared.put(field.property, null);
            assertThat(metadataOf(reload(nulled))).isEqualTo(cleared);
            assertThat(events.countByEvidenceId(nulled.getId())).isEqualTo(1L);
        } else {
            explicitNull
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(VALIDATION_ERROR));
            assertThat(metadataOf(reload(nulled))).isEqualTo(baseline);
            assertThat(events.countByEvidenceId(nulled.getId())).isZero();
        }

        DigitalEvidence blanked = freshEvidence();
        ResultActions blank = perform(blanked, manager, quoted(field.property, "   "));
        if (field.blankClears) {
            blank.andExpect(status().isOk());
            Map<String, Object> cleared = new LinkedHashMap<>(baseline);
            cleared.put(field.property, null);
            assertThat(metadataOf(reload(blanked))).isEqualTo(cleared);
        } else {
            blank.andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(VALIDATION_ERROR));
            assertThat(metadataOf(reload(blanked))).isEqualTo(baseline);
            assertThat(events.countByEvidenceId(blanked.getId())).isZero();
        }
    }

    /** Lengths are checked after trimming, so surrounding whitespace never consumes the documented budget. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = Patchable.class,
            mode = EnumSource.Mode.EXCLUDE,
            names = {"SOURCE_TYPE", "ACQUISITION_METHOD", "ACQUIRED_AT"})
    void validatesTextLengthBoundariesAfterNormalization(Patchable field) throws Exception {
        DigitalEvidence longest = freshEvidence();
        String maximum = "x".repeat(field.maximumLength);

        perform(longest, manager, quoted(field.property, "   " + maximum + "   "))
                .andExpect(status().isOk());

        assertThat(metadataOf(reload(longest)).get(field.property)).isEqualTo(maximum);

        DigitalEvidence tooLong = freshEvidence();
        perform(tooLong, manager, quoted(field.property, "x".repeat(field.maximumLength + 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(VALIDATION_ERROR));
        assertThat(events.countByEvidenceId(tooLong.getId())).isZero();
    }

    /** Everything outside the fourteen descriptive fields is immutable and is rejected by strict deserialization. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "referenceTag",
                "caseId",
                "status",
                "currentHolder",
                "currentHolderId",
                "uploadedBy",
                "originalFilename",
                "fileExtension",
                "mediaType",
                "fileSize",
                "contentSha256",
                "contextualSha256",
                "storageKey",
                "createdAt",
                "updatedAt",
                "version",
                "custodyEventCount",
                "custodyChainHeadHash",
                "metadata",
                "op"
            })
    void rejectsUnknownAndImmutableProperties(String property) throws Exception {
        DigitalEvidence target = freshEvidence();
        Map<String, Object> baseline = metadataOf(target);

        perform(
                        target,
                        manager,
                        "{\"%s\":\"value\",\"title\":\"New title\",\"reason\":\"%s\"}".formatted(property, REASON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(VALIDATION_ERROR));

        assertThat(metadataOf(reload(target))).isEqualTo(baseline);
        assertThat(events.countByEvidenceId(target.getId())).isZero();
    }

    @Test
    void rejectsRequiredFieldViolationsUnsupportedEnumsReasonBoundariesAndTheAcquiredAtBoundary() throws Exception {
        DigitalEvidence target = freshEvidence();
        Map<String, Object> baseline = metadataOf(target);
        Instant createdAt = reload(target).getCreatedAt();

        for (String invalid : new String[] {
            "{\"title\":\"ab\",\"reason\":\"%s\"}".formatted(REASON),
            "{\"sourceType\":\"TELEPATHY\",\"reason\":\"%s\"}".formatted(REASON),
            "{\"acquisitionMethod\":\"OSMOSIS\",\"reason\":\"%s\"}".formatted(REASON),
            "{\"sourceType\":12,\"reason\":\"%s\"}".formatted(REASON),
            "{\"acquiredAt\":\"not-an-instant\",\"reason\":\"%s\"}".formatted(REASON),
            "{\"acquiredAt\":\"%s\"}".formatted(createdAt),
            "{\"title\":\"New title\",\"reason\":null}",
            "{\"title\":\"New title\",\"reason\":\"   \"}",
            "{\"title\":\"New title\",\"reason\":\"%s\"}".formatted("y".repeat(1001)),
            "[]",
            "{\"acquiredAt\":\"%s\",\"reason\":\"%s\"}".formatted(createdAt.plusMillis(1), REASON)
        }) {
            perform(target, manager, invalid)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(VALIDATION_ERROR));
        }
        assertThat(metadataOf(reload(target))).isEqualTo(baseline);
        assertThat(events.countByEvidenceId(target.getId())).isZero();

        perform(target, manager, "{\"acquiredAt\":\"%s\",\"reason\":\"%s\"}".formatted(createdAt, "y".repeat(1000)))
                .andExpect(status().isOk());
        assertThat(reload(target).getAcquiredAt()).isEqualTo(createdAt);

        DigitalEvidence shortestTitle = freshEvidence();
        perform(shortestTitle, manager, "{\"title\":\"abc\",\"reason\":\" z \"}")
                .andExpect(status().isOk());
        assertThat(reload(shortestTitle).getTitle()).isEqualTo("abc");
    }

    @Test
    void returnsTheCanonicalEventLocationASanitizedBodyAndPreservesUnicodeAndCase() throws Exception {
        DigitalEvidence target = freshEvidence();
        String unicodeTitle = "Imágen forense — ФОРЕНЗИКА 🔬";

        MvcResult result = perform(
                        target,
                        officer,
                        "{\"title\":\"%s\",\"acquisitionNotes\":null,\"reason\":\"%s\"}"
                                .formatted(unicodeTitle, REASON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(2)))
                .andExpect(jsonPath("$.evidence.title").value(unicodeTitle))
                .andExpect(jsonPath("$.evidence.acquisitionNotes").doesNotExist())
                .andExpect(jsonPath("$.evidence.description").value(DESCRIPTION))
                .andExpect(jsonPath("$.evidence.status").value("IN_CUSTODY"))
                .andExpect(jsonPath("$.evidence.storageKey").doesNotExist())
                .andExpect(jsonPath("$.evidence.version").doesNotExist())
                .andExpect(jsonPath("$.evidence.custodyEventCount").doesNotExist())
                .andExpect(jsonPath("$.evidence.custodyChainHeadHash").doesNotExist())
                .andExpect(jsonPath("$.eventSummary.eventType").value("METADATA_UPDATED"))
                .andExpect(jsonPath("$.eventSummary.sequenceNumber").value(1))
                .andExpect(jsonPath("$.eventSummary.payload").doesNotExist())
                .andExpect(jsonPath("$.eventSummary.payloadJson").doesNotExist())
                .andReturn();

        Map<String, Object> response = readJson(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> eventSummary = (Map<String, Object>) response.get("eventSummary");
        assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo("/api/v1/evidences/%s/events/%s".formatted(target.getId(), eventSummary.get("id")));
        assertThat(reload(target).getTitle()).isEqualTo(unicodeTitle);
    }

    @Test
    void rejectsANoOpPatchWithoutTouchingTheAggregateOrTheChain() throws Exception {
        DigitalEvidence target = freshEvidence();
        DigitalEvidence stored = reload(target);

        for (String noOp : new String[] {
            "{\"reason\":\"%s\"}".formatted(REASON),
            "{\"title\":\"   %s   \",\"reason\":\"%s\"}".formatted(TITLE, REASON),
            "{\"sourceType\":\"%s\",\"acquisitionMethod\":\"%s\",\"reason\":\"%s\"}"
                    .formatted(SOURCE_TYPE, ACQUISITION_METHOD, REASON)
        }) {
            perform(target, manager, noOp)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/metadata-update-no-op"));
        }

        DigitalEvidence unchanged = reload(target);
        assertThat(events.countByEvidenceId(target.getId())).isZero();
        assertThat(unchanged.getCustodyEventCount()).isZero();
        assertThat(unchanged.getCustodyChainHeadHash()).isEqualTo(CustodyEventHashing.ZERO_HASH);
        assertThat(unchanged.getUpdatedAt()).isEqualTo(stored.getUpdatedAt());
        assertThat(unchanged.getVersion()).isEqualTo(stored.getVersion());
        assertThat(metadataOf(unchanged)).isEqualTo(metadataOf(stored));
    }

    @Test
    void refusesSealedReleasedAndClosedCaseEvidenceWithoutAppendingAnyEvent() throws Exception {
        DigitalEvidence sealed = freshEvidence();
        DigitalEvidence managedSealed = evidences.findById(sealed.getId()).orElseThrow();
        managedSealed.seal();
        evidences.saveAndFlush(managedSealed);
        DigitalEvidence released = freshEvidence();
        DigitalEvidence managedReleased = evidences.findById(released.getId()).orElseThrow();
        managedReleased.release();
        evidences.saveAndFlush(managedReleased);

        for (DigitalEvidence blocked : new DigitalEvidence[] {sealed, released}) {
            perform(blocked, manager, quoted("title", "Blocked title"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/invalid-evidence-state"));
            assertThat(events.countByEvidenceId(blocked.getId())).isZero();
        }

        DigitalEvidence inClosedCase = freshEvidence();
        CustodyCase managedCase = custodyCases.findById(owningCase.getId()).orElseThrow();
        managedCase.close();
        custodyCases.saveAndFlush(managedCase);

        perform(inClosedCase, manager, quoted("title", "Closed case title"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/case-closed"));
        assertThat(events.countByEvidenceId(inClosedCase.getId())).isZero();
        assertThat(reload(inClosedCase).getTitle()).isEqualTo(TITLE);
    }

    @Test
    void hidesEvidenceIdenticallyForNonMembersAndMissingIdentifiersAndForbidsVisibleAuditors() throws Exception {
        DigitalEvidence target = freshEvidence();
        String body = quoted("title", "Unauthorized title");

        String hidden = perform(target, outsider, body)
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String missing = mockMvc.perform(patch(METADATA_PATH, UUID.randomUUID())
                        .header("Authorization", bearer(outsider))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(withoutVolatileFields(hidden)).isEqualTo(withoutVolatileFields(missing));
        perform(target, auditor, body)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://proofchain.dev/problems/access-denied"));
        mockMvc.perform(patch(METADATA_PATH, target.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
        assertThat(events.countByEvidenceId(target.getId())).isZero();
        assertThat(reload(target).getTitle()).isEqualTo(TITLE);
    }

    @Test
    void documentsTheCanonicalMetadataOperationAndNoAlias() throws Exception {
        String operation = "$.paths['" + METADATA_PATH + "'].patch";
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(operation + ".operationId").value("updateEvidenceMetadata"))
                .andExpect(jsonPath(operation + ".requestBody.content['application/json'].schema.$ref")
                        .value("#/components/schemas/PatchEvidenceMetadataRequest"))
                .andExpect(jsonPath(operation + ".responses['200'].headers.Location")
                        .exists())
                .andExpect(jsonPath(operation + ".responses['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/EvidenceOperationResponse"))
                .andExpect(jsonPath(operation + ".responses['400']").exists())
                .andExpect(jsonPath(operation + ".responses['401']").exists())
                .andExpect(jsonPath(operation + ".responses['403']").exists())
                .andExpect(jsonPath(operation + ".responses['404']").exists())
                .andExpect(jsonPath(operation + ".responses['409']").exists())
                .andExpect(jsonPath(operation + ".responses['500']").exists())
                .andExpect(jsonPath("$.components.schemas.PatchEvidenceMetadataRequest.additionalProperties")
                        .value(false))
                .andExpect(jsonPath("$.components.schemas.PatchEvidenceMetadataRequest.required", hasSize(1)))
                .andExpect(jsonPath("$.components.schemas.PatchEvidenceMetadataRequest.properties.*", hasSize(15)))
                .andExpect(jsonPath("$.components.schemas.PatchEvidenceMetadataRequest.properties.title.maxLength")
                        .value(200))
                .andExpect(jsonPath("$.paths['" + METADATA_PATH + "'].put").doesNotExist())
                .andExpect(jsonPath("$.paths['" + METADATA_PATH + "'].post").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/evidences/{evidenceId}'].patch")
                        .doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/cases/{caseId}/evidences/{evidenceId}/metadata']")
                        .doesNotExist());
    }

    /** The fourteen modifiable descriptive fields with their normalization and presence semantics. */
    enum Patchable {
        TITLE(
                "title",
                "Updated forensic title",
                "Updated forensic title",
                false,
                false,
                200,
                DigitalEvidence::getTitle),
        DESCRIPTION(
                "description",
                "Updated description",
                "Updated description",
                true,
                true,
                2000,
                DigitalEvidence::getDescription),
        SOURCE_TYPE(
                "sourceType",
                "CLOUD_SERVICE",
                SourceType.CLOUD_SERVICE,
                false,
                false,
                0,
                DigitalEvidence::getSourceType),
        SOURCE_DESCRIPTION(
                "sourceDescription",
                "Tablet seized in the office.",
                "Tablet seized in the office.",
                true,
                true,
                500,
                DigitalEvidence::getSourceDescription),
        SOURCE_MANUFACTURER(
                "sourceManufacturer",
                "OTHERCORP",
                "OTHERCORP",
                true,
                true,
                100,
                DigitalEvidence::getSourceManufacturer),
        SOURCE_MODEL("sourceModel", "Z9-2027", "Z9-2027", true, true, 100, DigitalEvidence::getSourceModel),
        SOURCE_SERIAL_NUMBER(
                "sourceSerialNumber",
                "SN-9999-ZZ",
                "SN-9999-ZZ",
                true,
                true,
                200,
                DigitalEvidence::getSourceSerialNumber),
        SOURCE_LOGICAL_IDENTIFIER(
                "sourceLogicalIdentifier",
                "host-7/volume-3",
                "host-7/volume-3",
                true,
                true,
                300,
                DigitalEvidence::getSourceLogicalIdentifier),
        ACQUISITION_METHOD(
                "acquisitionMethod",
                "LOGICAL",
                AcquisitionMethod.LOGICAL,
                false,
                false,
                0,
                DigitalEvidence::getAcquisitionMethod),
        ACQUIRED_AT(
                "acquiredAt",
                "2019-06-01T10:00:00Z",
                Instant.parse("2019-06-01T10:00:00Z"),
                true,
                false,
                0,
                DigitalEvidence::getAcquiredAt),
        ACQUISITION_LOCATION(
                "acquisitionLocation",
                "Evidence room C",
                "Evidence room C",
                true,
                true,
                300,
                DigitalEvidence::getAcquisitionLocation),
        ACQUISITION_TOOL_NAME(
                "acquisitionToolName",
                "OtherTool",
                "OtherTool",
                true,
                true,
                200,
                DigitalEvidence::getAcquisitionToolName),
        ACQUISITION_TOOL_VERSION(
                "acquisitionToolVersion",
                "4.0.0",
                "4.0.0",
                true,
                true,
                100,
                DigitalEvidence::getAcquisitionToolVersion),
        ACQUISITION_NOTES(
                "acquisitionNotes",
                "Second review completed.",
                "Second review completed.",
                true,
                true,
                2000,
                DigitalEvidence::getAcquisitionNotes);

        private final String property;
        private final String newJsonValue;
        private final Object newValue;
        private final boolean nullClears;
        private final boolean blankClears;
        private final int maximumLength;
        private final Function<DigitalEvidence, Object> reader;

        Patchable(
                String property,
                String newJsonValue,
                Object newValue,
                boolean nullClears,
                boolean blankClears,
                int maximumLength,
                Function<DigitalEvidence, Object> reader) {
            this.property = property;
            this.newJsonValue = newJsonValue;
            this.newValue = newValue;
            this.nullClears = nullClears;
            this.blankClears = blankClears;
            this.maximumLength = maximumLength;
            this.reader = reader;
        }
    }

    private static Map<String, Object> metadataOf(DigitalEvidence evidence) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (Patchable field : Patchable.values()) {
            metadata.put(field.property, field.reader.apply(evidence));
        }
        return metadata;
    }

    private static String quoted(String property, String value) {
        return "{\"%s\":\"%s\",\"reason\":\"%s\"}".formatted(property, value, REASON);
    }

    private ResultActions perform(DigitalEvidence evidence, Operator actor, String body) throws Exception {
        return mockMvc.perform(patch(METADATA_PATH, evidence.getId())
                .header("Authorization", bearer(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private DigitalEvidence reload(DigitalEvidence evidence) {
        return evidences.findByIdForVisibility(evidence.getId()).orElseThrow();
    }

    private DigitalEvidence freshEvidence() {
        String referenceTag = "MD" + referenceTags.incrementAndGet();
        return evidences.saveAndFlush(DigitalEvidence.create(
                owningCase,
                officer,
                officer,
                referenceTag,
                TITLE,
                DESCRIPTION,
                SOURCE_TYPE,
                SOURCE_DESCRIPTION,
                SOURCE_MANUFACTURER,
                SOURCE_MODEL,
                SOURCE_SERIAL_NUMBER,
                SOURCE_LOGICAL_IDENTIFIER,
                ACQUISITION_METHOD,
                ACQUISITION_LOCATION,
                ACQUISITION_TOOL_NAME,
                ACQUISITION_TOOL_VERSION,
                ACQUISITION_NOTES,
                ACQUIRED_AT,
                "disk-image.E01",
                "application/octet-stream",
                4096L,
                "b".repeat(64),
                "c".repeat(64),
                "cases/case-id/evidences/" + referenceTag + "/content.bin"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(MvcResult result) throws Exception {
        return new LinkedHashMap<>(JSON.readValue(result.getResponse().getContentAsString(), Map.class));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> withoutVolatileFields(String body) {
        Map<String, Object> problem = new LinkedHashMap<>(JSON.readValue(body, Map.class));
        problem.remove("timestamp");
        problem.remove("instance");
        return problem;
    }

    private void assign(Operator... members) {
        for (Operator member : members) {
            memberships.save(CaseMembership.assign(owningCase, member, manager));
        }
        memberships.flush();
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
        jdbcTemplate.execute("TRUNCATE TABLE custody_events");
        evidences.deleteAllInBatch();
        memberships.deleteAllInBatch();
        custodyCases.deleteAllInBatch();
        operators.deleteAllInBatch();
    }
}
