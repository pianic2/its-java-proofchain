package it.itsprodigi.proofchain.evidence.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.evidence.application.EvidenceCommandResponseMapper;
import it.itsprodigi.proofchain.evidence.application.EvidenceMetadataUpdateService;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Canonical descriptive metadata update endpoint.
 *
 * <p>There is no generic PATCH, JSON Patch, JSON Merge Patch, PUT replacement, case-nested alias or arbitrary metadata
 * map: the strict document below is the only way to change descriptive evidence metadata.
 */
@RestController
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Digital evidence", description = "Digital evidence registration, metadata and content APIs.")
public class EvidenceMetadataUpdateController {

    private final EvidenceMetadataUpdateService service;
    private final EvidenceCommandResponseMapper responses;

    public EvidenceMetadataUpdateController(
            EvidenceMetadataUpdateService service, EvidenceCommandResponseMapper responses) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.responses = Objects.requireNonNull(responses, "responses must not be null");
    }

    @PatchMapping(
            path = "/api/v1/evidences/{evidenceId}/metadata",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "updateEvidenceMetadata",
            summary = "Update descriptive metadata of visible evidence",
            description =
                    "Changes only the fourteen descriptive metadata fields and appends exactly one METADATA_UPDATED custody event in the same transaction. Semantics are presence-aware: an absent property preserves the current value, an explicit null clears an optional value, blank optional text is trimmed to null, and lengths are validated after normalization. Unknown or immutable properties are rejected. Allowed to ADMIN globally and to a member CASE_MANAGER or EVIDENCE_OFFICER; an AUDITOR that can see the evidence is forbidden. The evidence must be IN_CUSTODY and its case OPEN. When the complete normalized before and after snapshots are equal the request is a conflict and appends nothing. Hidden and nonexistent evidence are indistinguishable.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Descriptive metadata updated and custody event appended",
                headers =
                        @Header(
                                name = "Location",
                                description = "Canonical URI of the appended custody event",
                                required = true,
                                schema = @Schema(type = "string", format = "uri")),
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = EvidenceOperationResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description =
                        "Invalid patch document, unknown or immutable property, unsupported enum value, invalid length after normalization, acquiredAt later than createdAt, or invalid reason",
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
                responseCode = "403",
                description = "Visible evidence metadata cannot be updated by the caller",
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
                responseCode = "409",
                description =
                        "Closed case, invalid evidence state, no-op metadata update, or custody event concurrency conflict",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "500",
                description = "Custody event persistence failure",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<EvidenceOperationResponse> updateMetadata(
            @Parameter(description = "Digital evidence identifier", example = "6f674949-c508-49bf-a160-ef720f9b51ee")
                    @PathVariable
                    UUID evidenceId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description =
                                    "Strict presence-aware patch carrying only descriptive metadata fields and the operational reason.",
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(implementation = PatchEvidenceMetadataRequest.class),
                                            examples =
                                                    @ExampleObject(
                                                            name = "metadata",
                                                            value =
                                                                    "{\"acquisitionToolVersion\":\"3.1.4\",\"acquisitionNotes\":null,\"reason\":\"Corrected the acquisition tool version after the laboratory review.\"}")))
                    @RequestBody
                    PatchEvidenceMetadataRequest request,
            @AuthenticationPrincipal AuthenticatedOperator actor) {
        EvidenceOperationResponse response = service.update(evidenceId, request, actor);
        return ResponseEntity.ok().location(responses.location(response)).body(response);
    }
}
