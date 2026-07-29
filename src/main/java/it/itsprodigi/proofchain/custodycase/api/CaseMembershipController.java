package it.itsprodigi.proofchain.custodycase.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.custodycase.application.CaseMembershipService;
import it.itsprodigi.proofchain.custodycase.application.MembershipAssignmentResult;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cases/{caseId}/members")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Custody cases", description = "Custody case metadata, lifecycle, visibility, and membership APIs.")
public class CaseMembershipController {

    private final CaseMembershipService service;

    public CaseMembershipController(CaseMembershipService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(
            operationId = "listCustodyCaseMembers",
            summary = "List custody case members",
            description =
                    "Returns members to ADMIN callers or assigned members, ordered by assignedAt ascending and membership id ascending. Inaccessible cases are hidden as 404.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Ordered custody case memberships",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                array = @ArraySchema(schema = @Schema(implementation = MembershipResponse.class)))),
        @ApiResponse(
                responseCode = "400",
                description = "Invalid identifier",
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
    public List<MembershipResponse> list(
            @Parameter(description = "Custody case identifier", example = "1ca01c67-75b9-48e3-a2ed-72259373c67c")
                    @PathVariable
                    UUID caseId,
            @AuthenticationPrincipal AuthenticatedOperator actor) {
        return service.list(caseId, actor);
    }

    @PutMapping("/{operatorId}")
    @Operation(
            operationId = "assignCustodyCaseMember",
            summary = "Assign a custody case member",
            description =
                    "Creates one membership for an ACTIVE non-ADMIN operator or returns the unchanged existing membership idempotently. ADMIN callers have global authority; CASE_MANAGER callers must be members of an OPEN case.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Membership already existed",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = MembershipResponse.class))),
        @ApiResponse(
                responseCode = "201",
                description = "Membership created",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = MembershipResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Invalid identifier",
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
                description = "Visible custody case cannot be managed by the caller",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Custody case hidden/not found or target operator not found",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "409",
                description = "Closed case, invalid target state/role, or concurrent conflict",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<MembershipResponse> assign(
            @Parameter(description = "Custody case identifier", example = "1ca01c67-75b9-48e3-a2ed-72259373c67c")
                    @PathVariable
                    UUID caseId,
            @Parameter(description = "Operator identifier", example = "9a3b8bf4-1d96-4a1e-810e-5a2f8b6ee2b1")
                    @PathVariable
                    UUID operatorId,
            @AuthenticationPrincipal AuthenticatedOperator actor) {
        MembershipAssignmentResult result = service.assign(caseId, operatorId, actor);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.membership());
    }

    @DeleteMapping("/{operatorId}")
    @Operation(
            operationId = "removeCustodyCaseMember",
            summary = "Remove a custody case member",
            description =
                    "Removes a membership idempotently from an OPEN case while preserving at least one ACTIVE responsible manager. ADMIN callers have global authority; CASE_MANAGER callers must be members.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Membership absent after the operation"),
        @ApiResponse(
                responseCode = "400",
                description = "Invalid identifier",
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
                description = "Visible custody case cannot be managed by the caller",
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
                description = "Closed case or last responsible manager removal",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<Void> remove(
            @Parameter(description = "Custody case identifier", example = "1ca01c67-75b9-48e3-a2ed-72259373c67c")
                    @PathVariable
                    UUID caseId,
            @Parameter(description = "Operator identifier", example = "9a3b8bf4-1d96-4a1e-810e-5a2f8b6ee2b1")
                    @PathVariable
                    UUID operatorId,
            @AuthenticationPrincipal AuthenticatedOperator actor) {
        service.remove(caseId, operatorId, actor);
        return ResponseEntity.noContent().build();
    }
}
