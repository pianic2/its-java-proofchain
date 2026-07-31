package it.itsprodigi.proofchain.evidence.api;

/**
 * Rendered OpenAPI examples for the five operational custody commands.
 *
 * <p>Every value is synthetic: the identifiers, hashes, filenames and operator names below belong to no real case,
 * evidence item, operator or stored file. Each constant is a compile-time constant expression, which is what allows it
 * to be referenced from an {@code @ExampleObject} annotation, and they live here rather than inline so the controllers
 * stay readable.
 */
final class OperationalCustodyExamples {

    private OperationalCustodyExamples() {}

    // --- request documents -------------------------------------------------------------------------------------

    static final String TRANSFER_REQUEST = """
            {"newHolderId":"b32ecaa9-8c4c-43d7-bdc0-28f9e38f3c37",\
            "reason":"Handover to the laboratory analyst."}""";

    static final String METADATA_REQUEST_SIMPLE = """
            {"acquisitionToolVersion":"3.1.4","acquisitionNotes":null,\
            "reason":"Corrected the acquisition tool version after the laboratory review."}""";

    /**
     * The three distinct presence shapes in one document: {@code title} is absent and therefore preserved,
     * {@code description} is an explicit null and therefore cleared, {@code acquisitionNotes} is blank and therefore
     * normalized to null, and {@code acquisitionToolVersion} is trimmed before comparison and storage.
     */
    static final String METADATA_REQUEST_PRESENCE = """
            {"description":null,"acquisitionToolVersion":"  3.1.4  ","acquisitionNotes":"   ",\
            "reason":"Cleared the stale review note and corrected the tool version."}""";

    static final String SEAL_REQUEST = """
            {"reason":"Analysis completed; the working copy is sealed for preservation."}""";

    static final String RELEASE_REQUEST = """
            {"reason":"Proceedings closed; custody of the evidence is terminated."}""";

    // --- success bodies ----------------------------------------------------------------------------------------

    private static final String EVIDENCE_HEAD = """
            {"id":"6f674949-c508-49bf-a160-ef720f9b51ee",\
            "caseId":"1ca01c67-75b9-48e3-a2ed-72259373c67c",\
            "referenceTag":"DEMO-01","title":"Disk image","description":null,""";

    private static final String HOLDER_ANALYST = """
            {"id":"b32ecaa9-8c4c-43d7-bdc0-28f9e38f3c37","username":"lab.analyst",\
            "firstName":"Lab","lastName":"Analyst","role":"EVIDENCE_OFFICER","status":"ACTIVE"}""";

    private static final String EVIDENCE_TAIL = """
            ,"uploadedBy":{"id":"eb8c2d1d-3f4a-4a8e-88c8-2e70b08f9714","username":"field.officer",\
            "firstName":"Field","lastName":"Officer","role":"EVIDENCE_OFFICER","status":"ACTIVE"},\
            "createdAt":"2026-07-30T09:00:00Z","updatedAt":"2026-07-30T09:15:00.123456Z",\
            "sourceType":"DEVICE","sourceDescription":null,"sourceManufacturer":null,"sourceModel":null,\
            "sourceSerialNumber":null,"sourceLogicalIdentifier":null,"acquisitionMethod":"PHYSICAL",\
            "acquiredAt":null,"acquisitionLocation":null,"acquisitionToolName":"AcquireTool",\
            "acquisitionToolVersion":"3.1.4","acquisitionNotes":null,\
            "originalFilename":"demo-evidence.bin","fileExtension":"bin",\
            "mediaType":"application/octet-stream","fileSize":25,\
            "contentSha256":"9ac0e4751fa6d0fca5082060cd44e943660f510cc4af096424b6396d52327262",\
            "contextualSha256":"665dea9d0df23b5ea6e6a3b18f424270a3d9c5dd8e92e2272bfed580047a1b57"}""";

    private static final String EVIDENCE_IN_CUSTODY =
            EVIDENCE_HEAD + "\"status\":\"IN_CUSTODY\",\"currentHolder\":" + HOLDER_ANALYST + EVIDENCE_TAIL;

    private static final String EVIDENCE_SEALED =
            EVIDENCE_HEAD + "\"status\":\"SEALED\",\"currentHolder\":" + HOLDER_ANALYST + EVIDENCE_TAIL;

    private static final String EVIDENCE_RELEASED =
            EVIDENCE_HEAD + "\"status\":\"RELEASED\",\"currentHolder\":null" + EVIDENCE_TAIL;

    private static final String EVENT_HEAD = """
            {"id":"ac2f7e10-5f6f-4f0e-9d1b-0f5a3a1c9d21",\
            "caseId":"1ca01c67-75b9-48e3-a2ed-72259373c67c",\
            "evidenceId":"6f674949-c508-49bf-a160-ef720f9b51ee","sequenceNumber":""";

    private static final String EVENT_TAIL = """
            "operatorId":"eb8c2d1d-3f4a-4a8e-88c8-2e70b08f9714","actorRole":"CASE_MANAGER",\
            "occurredAt":"2026-07-30T09:15:00.123456Z","hashVersion":1,"payloadVersion":1,\
            "previousHash":"7f3eaf87d89253f7cd8d7bde43310f61efb87abb62ca9617ec2c0d46cd4f494c",\
            "eventHash":"1d5c6b0f4a8e2c93b7d40f1e6a2c8b5f3e97d0a4c1b8e6f2d3a5c7091e4b8d62"}""";

    private static final String EVENT_TRANSFERRED =
            EVENT_HEAD + "2,\"eventType\":\"CUSTODY_TRANSFERRED\"," + EVENT_TAIL;

    private static final String EVENT_METADATA = EVENT_HEAD + "3,\"eventType\":\"METADATA_UPDATED\"," + EVENT_TAIL;

    private static final String EVENT_SEALED = EVENT_HEAD + "4,\"eventType\":\"EVIDENCE_SEALED\"," + EVENT_TAIL;

    private static final String EVENT_RELEASED_FROM_CUSTODY =
            EVENT_HEAD + "3,\"eventType\":\"EVIDENCE_RELEASED\"," + EVENT_TAIL;

    private static final String EVENT_RELEASED_FROM_SEALED =
            EVENT_HEAD + "5,\"eventType\":\"EVIDENCE_RELEASED\"," + EVENT_TAIL;

    static final String TRANSFER_SUCCESS =
            "{\"evidence\":" + EVIDENCE_IN_CUSTODY + ",\"eventSummary\":" + EVENT_TRANSFERRED + "}";

    static final String METADATA_SUCCESS =
            "{\"evidence\":" + EVIDENCE_IN_CUSTODY + ",\"eventSummary\":" + EVENT_METADATA + "}";

    static final String SEAL_SUCCESS = "{\"evidence\":" + EVIDENCE_SEALED + ",\"eventSummary\":" + EVENT_SEALED + "}";

    /** Release from {@code IN_CUSTODY}: the holder is cleared, while the event still records the previous holder. */
    static final String RELEASE_FROM_IN_CUSTODY =
            "{\"evidence\":" + EVIDENCE_RELEASED + ",\"eventSummary\":" + EVENT_RELEASED_FROM_CUSTODY + "}";

    /** Release from {@code SEALED}: the same terminal outcome reached from the other permitted source status. */
    static final String RELEASE_FROM_SEALED =
            "{\"evidence\":" + EVIDENCE_RELEASED + ",\"eventSummary\":" + EVENT_RELEASED_FROM_SEALED + "}";

    // --- Problem Details ---------------------------------------------------------------------------------------

    private static final String PROBLEM_PREFIX = "{\"type\":\"https://proofchain.dev/problems/";
    private static final String INSTANCE_PREFIX =
            "\"instance\":\"/api/v1/evidences/6f674949-c508-49bf-a160-ef720f9b51ee/";
    private static final String TIMESTAMP = "\",\"timestamp\":\"2026-07-30T09:15:00.123456Z\"}";

    static final String TRANSFER_NO_OP = PROBLEM_PREFIX
            + "custody-transfer-no-op\",\"title\":\"Custody transfer no-op\",\"status\":409,"
            + "\"detail\":\"The requested holder already holds this evidence.\","
            + INSTANCE_PREFIX
            + "transfer"
            + TIMESTAMP;

    static final String TRANSFER_HOLDER_NOT_ELIGIBLE = PROBLEM_PREFIX
            + "holder-not-eligible\",\"title\":\"Evidence holder not eligible\",\"status\":409,"
            + "\"detail\":\"The requested holder is not eligible for this custody case.\","
            + INSTANCE_PREFIX
            + "transfer"
            + TIMESTAMP;

    static final String METADATA_NO_OP = PROBLEM_PREFIX
            + "metadata-update-no-op\",\"title\":\"Metadata update no-op\",\"status\":409,"
            + "\"detail\":\"The requested metadata already matches the current evidence metadata.\","
            + INSTANCE_PREFIX
            + "metadata"
            + TIMESTAMP;

    static final String METADATA_NOT_IN_CUSTODY = PROBLEM_PREFIX
            + "invalid-evidence-state\",\"title\":\"Invalid evidence state\",\"status\":409,"
            + "\"detail\":\"Only evidence in custody can change descriptive metadata.\","
            + INSTANCE_PREFIX
            + "metadata"
            + TIMESTAMP;

    static final String SEAL_HOLDER_NOT_ELIGIBLE = PROBLEM_PREFIX
            + "holder-not-eligible\",\"title\":\"Evidence holder not eligible\",\"status\":409,"
            + "\"detail\":\"The requested holder is not eligible for this custody case.\","
            + INSTANCE_PREFIX
            + "seal"
            + TIMESTAMP;

    static final String RELEASE_TERMINAL = PROBLEM_PREFIX
            + "invalid-evidence-state\",\"title\":\"Invalid evidence state\",\"status\":409,"
            + "\"detail\":\"Released evidence is terminal and cannot be modified.\","
            + INSTANCE_PREFIX
            + "release"
            + TIMESTAMP;

    static final String CASE_CLOSED = PROBLEM_PREFIX
            + "case-closed\",\"title\":\"Custody case closed\",\"status\":409,"
            + "\"detail\":\"The custody case is closed.\","
            + INSTANCE_PREFIX
            + "release"
            + TIMESTAMP;

    static final String HIDDEN_RESOURCE = PROBLEM_PREFIX
            + "resource-not-found\",\"title\":\"Resource not found\",\"status\":404,"
            + "\"detail\":\"The requested resource was not found.\","
            + INSTANCE_PREFIX
            + "transfer"
            + TIMESTAMP;

    static final String VISIBLE_FORBIDDEN = PROBLEM_PREFIX
            + "access-denied\",\"title\":\"Access denied\",\"status\":403,"
            + "\"detail\":\"The authenticated operator is not authorized to perform this operation.\","
            + INSTANCE_PREFIX
            + "transfer"
            + TIMESTAMP;

    static final String FILE_UNAVAILABLE = PROBLEM_PREFIX
            + "evidence-file-unavailable\",\"title\":\"Evidence file unavailable\",\"status\":500,"
            + "\"detail\":\"Evidence content is unavailable.\","
            + INSTANCE_PREFIX
            + "verify-integrity"
            + TIMESTAMP;
}
