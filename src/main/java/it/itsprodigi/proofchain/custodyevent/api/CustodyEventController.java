package it.itsprodigi.proofchain.custodyevent.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.custodyevent.application.CustodyEventQueryService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@SecurityRequirement(name = "bearerAuth")
@Tag(
        name = "Custody events",
        description =
                "Immutable custody-event timeline and detail APIs. Events cannot be created, updated or deleted through the public API.")
public class CustodyEventController {

    private static final String REGISTERED_EVENT_EXAMPLE = """
            {
              "id": "f24f1f96-2527-4b7d-bb1a-9781fc50cc07",
              "caseId": "1ca01c67-75b9-48e3-a2ed-72259373c67c",
              "evidenceId": "6f674949-c508-49bf-a160-ef720f9b51ee",
              "sequenceNumber": 1,
              "eventType": "EVIDENCE_REGISTERED",
              "operatorId": "eb8c2d1d-3f4a-4a8e-88c8-2e70b08f9714",
              "actorRole": "EVIDENCE_OFFICER",
              "occurredAt": "2026-07-29T12:34:56.123456Z",
              "hashVersion": 1,
              "payloadVersion": 1,
              "previousHash": "0000000000000000000000000000000000000000000000000000000000000000",
              "eventHash": "7f3eaf87d89253f7cd8d7bde43310f61efb87abb62ca9617ec2c0d46cd4f494c",
              "payload": {
                "backfilled": false,
                "referenceTag": "EVIDENCE-01",
                "title": "Forensic disk image",
                "description": null,
                "status": "IN_CUSTODY",
                "sourceType": "DEVICE",
                "sourceDescription": null,
                "sourceManufacturer": null,
                "sourceModel": null,
                "sourceSerialNumber": null,
                "sourceLogicalIdentifier": null,
                "acquisitionMethod": "PHYSICAL",
                "acquiredAt": "2026-07-29T11:30:00Z",
                "acquisitionLocation": null,
                "acquisitionToolName": null,
                "acquisitionToolVersion": null,
                "acquisitionNotes": null,
                "originalFilename": "disk-image.E01",
                "fileExtension": "e01",
                "mediaType": "application/octet-stream",
                "fileSize": 4096,
                "contentSha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "contextualSha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                "uploadedById": "eb8c2d1d-3f4a-4a8e-88c8-2e70b08f9714",
                "initialHolderId": "eb8c2d1d-3f4a-4a8e-88c8-2e70b08f9714"
              }
            }
            """;

    private final CustodyEventQueryService service;

    public CustodyEventController(CustodyEventQueryService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/evidences/{evidenceId}/events")
    @Operation(
            operationId = "listCustodyEvents",
            summary = "List the immutable custody-event timeline",
            description =
                    "Available globally to ADMIN and to every assigned member of the evidence case, including AUDITOR. Hidden and missing evidence are indistinguishable. Closed cases and all evidence lifecycle states remain readable. Paging defaults to page 0 and size 50, with a maximum size of 200. Sequence number ascending is the only order; every sort parameter and all filtering are rejected.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Visible custody-event page without payload bodies",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = CustodyEventPageResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Invalid identifier, pagination or unsupported sorting",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "401",
                description = "Authentication required",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Evidence hidden or not found",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "500",
                description = "Persisted custody-event protocol data cannot be read safely",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemDetail.class)))
    })
    public CustodyEventPageResponse list(
            @Parameter(description = "Digital evidence identifier", example = "6f674949-c508-49bf-a160-ef720f9b51ee")
                    @PathVariable
                    UUID evidenceId,
            @Parameter(description = "Zero-based page index", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size from 1 to 200", example = "50") @RequestParam(defaultValue = "50")
                    int size,
            @Parameter(description = "Unsupported; every occurrence is rejected") @RequestParam(required = false)
                    String sort,
            HttpServletRequest request,
            @AuthenticationPrincipal AuthenticatedOperator actor) {
        String[] sortValues = request.getParameterValues("sort");
        List<String> requestedSort = sortValues == null ? List.of() : Arrays.asList(sortValues);
        return service.list(evidenceId, page, size, requestedSort, actor);
    }

    @GetMapping("/api/v1/evidences/{evidenceId}/events/{eventId}")
    @Operation(
            operationId = "getCustodyEvent",
            summary = "Get immutable custody-event details",
            description =
                    "Returns the stored historical actor-role snapshot and the exact typed version-1 payload. Available globally to ADMIN and to every assigned case member. Hidden evidence uses the certified resource-not-found response. A missing event or an event paired with the wrong evidence uses event-not-found. Closed cases and RELEASED evidence remain readable.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Visible custody-event details",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = CustodyEventDetailResponse.class),
                                examples =
                                        @ExampleObject(
                                                name = "Evidence registration",
                                                value = REGISTERED_EVENT_EXAMPLE))),
        @ApiResponse(
                responseCode = "400",
                description = "Invalid evidence or event identifier",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "401",
                description = "Authentication required",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description =
                        "Evidence hidden or missing (resource-not-found), or event missing/mismatched (event-not-found)",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "500",
                description = "Persisted event type, payload or protocol version cannot be read safely",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemDetail.class)))
    })
    public CustodyEventDetailResponse get(
            @Parameter(description = "Digital evidence identifier", example = "6f674949-c508-49bf-a160-ef720f9b51ee")
                    @PathVariable
                    UUID evidenceId,
            @Parameter(description = "Custody event identifier", example = "f24f1f96-2527-4b7d-bb1a-9781fc50cc07")
                    @PathVariable
                    UUID eventId,
            @AuthenticationPrincipal AuthenticatedOperator actor) {
        return service.get(evidenceId, eventId, actor);
    }
}
