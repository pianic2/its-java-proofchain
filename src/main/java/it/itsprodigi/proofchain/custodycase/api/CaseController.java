package it.itsprodigi.proofchain.custodycase.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
public class CaseController {

    private final CustodyCaseService service;

    public CaseController(CustodyCaseService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(
            summary = "Create a custody case",
            description = "Atomically creates an OPEN case and its creator membership.")
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
            @Valid @RequestBody CreateCaseRequest request, @AuthenticationPrincipal AuthenticatedOperator actor) {
        CaseResponse response = service.create(request, actor);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{caseId}")
                .buildAndExpand(response.id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping
    @Operation(
            summary = "List accessible custody cases",
            description = "Returns a fixed-order page sorted by createdAt descending and id ascending.")
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
    @Operation(summary = "Get an accessible custody case")
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
    public CaseResponse get(@PathVariable UUID caseId, @AuthenticationPrincipal AuthenticatedOperator actor) {
        return service.get(caseId, actor);
    }

    @PatchMapping("/{caseId}")
    @Operation(
            summary = "Update custody case metadata",
            description = "Partially updates metadata on an OPEN custody case.")
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
            @PathVariable UUID caseId,
            @RequestBody PatchCaseMetadataRequest request,
            @AuthenticationPrincipal AuthenticatedOperator actor) {
        return service.updateMetadata(caseId, request, actor);
    }

    @PatchMapping("/{caseId}/status")
    @Operation(summary = "Close a custody case", description = "Irreversibly and idempotently targets CLOSED status.")
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
            @PathVariable UUID caseId,
            @Valid @RequestBody UpdateCaseStatusRequest request,
            @AuthenticationPrincipal AuthenticatedOperator actor) {
        return service.updateStatus(caseId, request, actor);
    }
}
