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
import it.itsprodigi.proofchain.evidence.application.EvidenceReleaseService;
import it.itsprodigi.proofchain.evidence.application.EvidenceSealService;
import jakarta.validation.Valid;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Canonical evidence lifecycle endpoints.
 *
 * <p>Exactly two commands exist. There is no status {@code PATCH}, no generic transition endpoint, no unseal, no
 * reopen, no case-nested alias, no bulk command and no administrative bypass route: the lifecycle graph
 * {@code IN_CUSTODY -> SEALED}, {@code IN_CUSTODY -> RELEASED}, {@code SEALED -> RELEASED} is the whole surface, and
 * {@code RELEASED} is terminal.
 */
@RestController
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Digital evidence", description = "Digital evidence registration, metadata and content APIs.")
public class EvidenceLifecycleController {

    private final EvidenceSealService seals;
    private final EvidenceReleaseService releases;
    private final EvidenceCommandResponseMapper responses;

    public EvidenceLifecycleController(
            EvidenceSealService seals, EvidenceReleaseService releases, EvidenceCommandResponseMapper responses) {
        this.seals = Objects.requireNonNull(seals, "seals must not be null");
        this.releases = Objects.requireNonNull(releases, "releases must not be null");
        this.responses = Objects.requireNonNull(responses, "responses must not be null");
    }

    @PostMapping(
            path = "/api/v1/evidences/{evidenceId}/seal",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "sealEvidence",
            summary = "Seal visible evidence in custody",
            description =
                    "Moves evidence from IN_CUSTODY to SEALED and appends exactly one EVIDENCE_SEALED custody event in the same transaction. Allowed to ADMIN globally, to a member CASE_MANAGER, and to a member EVIDENCE_OFFICER only while it is the current holder; AUDITOR is forbidden. The owning case must be OPEN, the evidence must be IN_CUSTODY and its current holder must still be an ACTIVE ADMIN, CASE_MANAGER or EVIDENCE_OFFICER member of the case, otherwise the same holder conflict is returned and an explicit recovery transfer is required first. Sealing never changes, clears or re-derives the holder, and already SEALED or terminal RELEASED evidence is rejected without appending any event. Hidden and nonexistent evidence are indistinguishable.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Evidence sealed and custody event appended",
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
                description = "Invalid seal document, unknown property, or invalid reason",
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
                description = "Visible evidence cannot be sealed by the caller",
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
                        "Closed case, invalid evidence state, ineligible current holder, or custody event concurrency conflict",
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
    public ResponseEntity<EvidenceOperationResponse> seal(
            @Parameter(description = "Digital evidence identifier", example = "6f674949-c508-49bf-a160-ef720f9b51ee")
                    @PathVariable
                    UUID evidenceId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "Strict seal command carrying only the operational reason.",
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(implementation = SealEvidenceRequest.class),
                                            examples =
                                                    @ExampleObject(
                                                            name = "seal",
                                                            value =
                                                                    "{\"reason\":\"Analysis completed; the working copy is sealed for preservation.\"}")))
                    @Valid
                    @RequestBody
                    SealEvidenceRequest request,
            @AuthenticationPrincipal AuthenticatedOperator actor) {
        EvidenceOperationResponse response = seals.seal(evidenceId, request, actor);
        return ResponseEntity.ok().location(responses.location(response)).body(response);
    }

    @PostMapping(
            path = "/api/v1/evidences/{evidenceId}/release",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "releaseEvidence",
            summary = "Release visible evidence from custody",
            description =
                    "Moves evidence from IN_CUSTODY or SEALED to the terminal RELEASED state, atomically clears the current holder and appends exactly one EVIDENCE_RELEASED custody event in the same transaction. Allowed only to ADMIN globally and to a member CASE_MANAGER; EVIDENCE_OFFICER and AUDITOR are forbidden. The owning case must be OPEN and the evidence must still have a holder, but that holder is deliberately not required to remain active or eligible, so custody can be terminated without a recovery transfer. A repeated release is a terminal transition and is rejected without appending any event; it is never reported as success. After release, transfer, metadata update, seal and further release are all forbidden and no operation can restore IN_CUSTODY or SEALED, while integrity verification, read, list, download, timeline and chain verification remain available under the existing visibility rules. Hidden and nonexistent evidence are indistinguishable.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Evidence released and custody event appended",
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
                description = "Invalid release document, unknown property, or invalid reason",
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
                description = "Visible evidence cannot be released by the caller",
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
                description = "Closed case, invalid evidence state, or custody event concurrency conflict",
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
    public ResponseEntity<EvidenceOperationResponse> release(
            @Parameter(description = "Digital evidence identifier", example = "6f674949-c508-49bf-a160-ef720f9b51ee")
                    @PathVariable
                    UUID evidenceId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "Strict release command carrying only the operational reason.",
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(implementation = ReleaseEvidenceRequest.class),
                                            examples =
                                                    @ExampleObject(
                                                            name = "release",
                                                            value =
                                                                    "{\"reason\":\"Proceedings closed; custody of the evidence is terminated.\"}")))
                    @Valid
                    @RequestBody
                    ReleaseEvidenceRequest request,
            @AuthenticationPrincipal AuthenticatedOperator actor) {
        EvidenceOperationResponse response = releases.release(evidenceId, request, actor);
        return ResponseEntity.ok().location(responses.location(response)).body(response);
    }
}
