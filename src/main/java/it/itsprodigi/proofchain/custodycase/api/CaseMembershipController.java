package it.itsprodigi.proofchain.custodycase.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
public class CaseMembershipController {

    private final CaseMembershipService service;

    public CaseMembershipController(CaseMembershipService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(
            summary = "List custody case members",
            description = "Returns members ordered by assignedAt ascending and membership id ascending.")
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
            @PathVariable UUID caseId, @AuthenticationPrincipal AuthenticatedOperator actor) {
        return service.list(caseId, actor);
    }

    @PutMapping("/{operatorId}")
    @Operation(
            summary = "Assign a custody case member",
            description = "Creates one membership or returns the unchanged existing membership idempotently.")
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
            @PathVariable UUID caseId,
            @PathVariable UUID operatorId,
            @AuthenticationPrincipal AuthenticatedOperator actor) {
        MembershipAssignmentResult result = service.assign(caseId, operatorId, actor);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.membership());
    }

    @DeleteMapping("/{operatorId}")
    @Operation(
            summary = "Remove a custody case member",
            description = "Removes a membership idempotently while preserving a responsible manager.")
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
            @PathVariable UUID caseId,
            @PathVariable UUID operatorId,
            @AuthenticationPrincipal AuthenticatedOperator actor) {
        service.remove(caseId, operatorId, actor);
        return ResponseEntity.noContent().build();
    }
}
