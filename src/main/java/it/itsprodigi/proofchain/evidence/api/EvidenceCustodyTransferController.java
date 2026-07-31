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
import it.itsprodigi.proofchain.evidence.application.CustodyTransferService;
import it.itsprodigi.proofchain.evidence.application.EvidenceCommandResponseMapper;
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

/** Canonical custody transfer endpoint. No case-nested alias, bulk transfer or administrative bypass exists. */
@RestController
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Digital evidence", description = "Digital evidence registration, metadata and content APIs.")
public class EvidenceCustodyTransferController {

    private final CustodyTransferService service;
    private final EvidenceCommandResponseMapper responses;

    public EvidenceCustodyTransferController(CustodyTransferService service, EvidenceCommandResponseMapper responses) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.responses = Objects.requireNonNull(responses, "responses must not be null");
    }

    @PostMapping(
            path = "/api/v1/evidences/{evidenceId}/transfer",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "transferEvidenceCustody",
            summary = "Transfer custody of visible evidence",
            description =
                    "Changes only the current holder and appends exactly one CUSTODY_TRANSFERRED custody event in the same transaction. Allowed to ADMIN globally, to a member CASE_MANAGER, and to a member EVIDENCE_OFFICER only while it is the current holder; ADMIN and member CASE_MANAGER may also recover evidence from a suspended or disabled holder. Evidence must be IN_CUSTODY or SEALED and its case must be OPEN; the status never changes, so SEALED evidence stays SEALED. The target must be an ACTIVE ADMIN, CASE_MANAGER or EVIDENCE_OFFICER member of the owning case; every ineligibility cause returns the same conflict. Hidden and nonexistent evidence are indistinguishable.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Custody transferred and custody event appended",
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
                description = "Invalid transfer document, unknown property, or invalid reason",
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
                description = "Visible evidence cannot be transferred by the caller",
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
                        "Closed case, invalid evidence state, ineligible holder, no-op transfer, or custody event concurrency conflict",
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
    public ResponseEntity<EvidenceOperationResponse> transfer(
            @Parameter(description = "Digital evidence identifier", example = "6f674949-c508-49bf-a160-ef720f9b51ee")
                    @PathVariable
                    UUID evidenceId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description =
                                    "Strict transfer command carrying only the target holder and the operational reason.",
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(implementation = TransferCustodyRequest.class),
                                            examples =
                                                    @ExampleObject(
                                                            name = "transfer",
                                                            value =
                                                                    "{\"newHolderId\":\"b32ecaa9-8c4c-43d7-bdc0-28f9e38f3c37\",\"reason\":\"Handover to the laboratory analyst.\"}")))
                    @Valid
                    @RequestBody
                    TransferCustodyRequest request,
            @AuthenticationPrincipal AuthenticatedOperator actor) {
        EvidenceOperationResponse response = service.transfer(evidenceId, request, actor);
        return ResponseEntity.ok().location(responses.location(response)).body(response);
    }
}
