package it.itsprodigi.proofchain.custodycase.api;

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
import it.itsprodigi.proofchain.custodycase.application.CustodyCaseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/cases")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Custody cases", description = "Custody case metadata, lifecycle, visibility, and membership APIs.")
public class CaseController {

    private final CustodyCaseService service;

    public CaseController(CustodyCaseService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(
            operationId = "createCustodyCase",
            summary = "Create a custody case",
            description =
                    "Atomically creates an OPEN case and its creator membership. Available to ACTIVE ADMIN and CASE_MANAGER operators.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Custody case created",
                headers =
                        @Header(
                                name = "Location",
                                description = "URI of the created custody case",
                                required = true,
                                schema = @Schema(type = "string", format = "uri")),
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = CaseResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Validation error",
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
                description = "Access denied",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<CaseResponse> create(
            @Valid
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "Strict custody case metadata document.",
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(implementation = CreateCaseRequest.class),
                                            examples =
                                                    @ExampleObject(
                                                            name = "case",
                                                            value =
                                                                    "{\"title\":\"Mobile device seizure\",\"description\":\"Device collected under warrant 2026-0142.\",\"authorityName\":\"Court of Rome\",\"externalReference\":\"WARRANT-2026-0142\",\"location\":\"Evidence room A\",\"priority\":\"HIGH\"}")))
                    @RequestBody
                    CreateCaseRequest request,
            @AuthenticationPrincipal AuthenticatedOperator actor) {
        CaseResponse response = service.create(request, actor);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{caseId}")
                .buildAndExpand(response.id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping
    @Operation(
            operationId = "listCustodyCases",
            summary = "List accessible custody cases",
            description =
                    "Returns all cases for ADMIN callers and membership-scoped cases for other roles, sorted by createdAt descending and id ascending. Client-controlled sorting is rejected.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Accessible custody case page",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = CasePageResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Invalid pagination or unsupported sorting",
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
                                schema = @Schema(implementation = ProblemDetail.class)))
    })
    public CasePageResponse list(
            @Parameter(description = "Zero-based page index", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size from 1 to 100", example = "20") @RequestParam(defaultValue = "20")
                    int size,
            @Parameter(description = "Unsupported; any occurrence is rejected") @RequestParam(required = false)
                    String sort,
            HttpServletRequest request,
            @AuthenticationPrincipal AuthenticatedOperator actor) {
        String[] sortValues = request.getParameterValues("sort");
        List<String> requestedSort = sortValues == null ? List.of() : Arrays.asList(sortValues);
        return service.list(page, size, requestedSort, actor);
    }

    @GetMapping("/{caseId}")
    @Operation(
            operationId = "getCustodyCase",
            summary = "Get an accessible custody case",
            description =
                    "Returns the case to ADMIN callers or assigned members. Existing but inaccessible cases use the same 404 response as missing cases.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Custody case details",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = CaseResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Invalid custody case identifier",
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
                description = "Custody case hidden or not found",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemDetail.class)))
    })
    public CaseResponse get(
            @Parameter(description = "Custody case identifier", example = "1ca01c67-75b9-48e3-a2ed-72259373c67c")
                    @PathVariable
                    UUID caseId,
            @AuthenticationPrincipal AuthenticatedOperator actor) {
        return service.get(caseId, actor);
    }

    @PatchMapping("/{caseId}")
    @Operation(
            operationId = "updateCustodyCaseMetadata",
            summary = "Update custody case metadata",
            description =
                    "Partially updates metadata on an OPEN custody case. ADMIN callers have global authority; CASE_MANAGER callers must be members.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Custody case metadata updated",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = CaseResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Invalid patch document",
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
                description = "Visible custody case cannot be modified by the caller",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Custody case hidden or not found",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "409",
                description = "Custody case closed or concurrently modified",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemDetail.class)))
    })
    public CaseResponse updateMetadata(
            @Parameter(description = "Custody case identifier", example = "1ca01c67-75b9-48e3-a2ed-72259373c67c")
                    @PathVariable
                    UUID caseId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description =
                                    "Strict partial update; omitted properties are preserved and explicit null clears nullable metadata.",
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(implementation = PatchCaseMetadataRequest.class),
                                            examples =
                                                    @ExampleObject(
                                                            name = "metadata",
                                                            value =
                                                                    "{\"description\":\"Supplemental examination approved.\",\"location\":null,\"priority\":\"CRITICAL\"}")))
                    @RequestBody
                    PatchCaseMetadataRequest request,
            @AuthenticationPrincipal AuthenticatedOperator actor) {
        return service.updateMetadata(caseId, request, actor);
    }

    @PatchMapping("/{caseId}/status")
    @Operation(
            operationId = "closeCustodyCase",
            summary = "Close a custody case",
            description =
                    "Irreversibly targets CLOSED status. Repeating CLOSED is idempotent; OPEN is an invalid transition. ADMIN callers have global authority; CASE_MANAGER callers must be members.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Custody case closed or already closed",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = CaseResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Invalid status document",
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
                description = "Visible custody case cannot be closed by the caller",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Custody case hidden or not found",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "409",
                description = "Invalid status transition or concurrent modification",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemDetail.class)))
    })
    public CaseResponse updateStatus(
            @Parameter(description = "Custody case identifier", example = "1ca01c67-75b9-48e3-a2ed-72259373c67c")
                    @PathVariable
                    UUID caseId,
            @Valid
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "Strict closure command.",
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(implementation = UpdateCaseStatusRequest.class),
                                            examples =
                                                    @ExampleObject(name = "close", value = "{\"status\":\"CLOSED\"}")))
                    @RequestBody
                    UpdateCaseStatusRequest request,
            @AuthenticationPrincipal AuthenticatedOperator actor) {
        return service.updateStatus(caseId, request, actor);
    }
}
