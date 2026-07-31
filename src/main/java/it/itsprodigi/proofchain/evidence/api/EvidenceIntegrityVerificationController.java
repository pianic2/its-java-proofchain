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
import it.itsprodigi.proofchain.evidence.application.EvidenceIntegrityVerificationService;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Canonical file-integrity verification endpoint. The request carries no body, and no GET alias, batch endpoint,
 * asynchronous job or persisted verification report exists.
 */
@RestController
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Digital evidence", description = "Digital evidence registration, metadata and content APIs.")
public class EvidenceIntegrityVerificationController {

    private static final String VALID_EXAMPLE = """
            {"evidenceId":"6f674949-c508-49bf-a160-ef720f9b51ee","valid":true,\
"expectedContentSha256":"9ac0e4751fa6d0fca5082060cd44e943660f510cc4af096424b6396d52327262",\
"actualContentSha256":"9ac0e4751fa6d0fca5082060cd44e943660f510cc4af096424b6396d52327262",\
"expectedFileSize":25,"actualFileSize":25,"verifiedAt":"2026-07-30T09:15:00.123456Z"}""";

    private static final String INVALID_EXAMPLE = """
            {"evidenceId":"6f674949-c508-49bf-a160-ef720f9b51ee","valid":false,\
"expectedContentSha256":"9ac0e4751fa6d0fca5082060cd44e943660f510cc4af096424b6396d52327262",\
"actualContentSha256":"3f79bb7b435b05321651daefd374cdc681dc06faa65e374e38337b88ca046dea",\
"expectedFileSize":25,"actualFileSize":12,"verifiedAt":"2026-07-30T09:15:00.123456Z"}""";

    private final EvidenceIntegrityVerificationService service;
    private final EvidenceCommandResponseMapper responses;

    public EvidenceIntegrityVerificationController(
            EvidenceIntegrityVerificationService service, EvidenceCommandResponseMapper responses) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.responses = Objects.requireNonNull(responses, "responses must not be null");
    }

    @PostMapping(path = "/api/v1/evidences/{evidenceId}/verify-integrity", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "verifyEvidenceIntegrity",
            summary = "Verify the stored file of visible evidence",
            description =
                    "Re-reads the exact stored bytes in one bounded-memory pass, recomputes their SHA-256 and counts them, compares both against the persisted evidence metadata and appends exactly one INTEGRITY_VERIFIED custody event in the same transaction. The request has no body. Allowed to ADMIN globally and to every assigned case member, including AUDITOR; hidden and nonexistent evidence are indistinguishable. The owning case must be OPEN because the command appends an event, while every evidence status is verifiable, including the terminal RELEASED. A conforming and a non-conforming result are both completed verifications and both answer 200 OK with the appended event; a mismatch is never reported as 409, 422 or 500. Only a technical inability to read the exact file is an error, and it appends no event and changes nothing. The persisted content hash, file size and stored bytes are never modified.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Verification completed; the stored content may conform or not",
                headers =
                        @Header(
                                name = "Location",
                                description = "Canonical URI of the appended custody event",
                                required = true,
                                schema = @Schema(type = "string", format = "uri")),
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = IntegrityVerificationResponse.class),
                                examples = {
                                    @ExampleObject(name = "Conforming content", value = VALID_EXAMPLE),
                                    @ExampleObject(name = "Non-conforming content", value = INVALID_EXAMPLE)
                                })),
        @ApiResponse(
                responseCode = "400",
                description = "Invalid evidence identifier",
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
                responseCode = "409",
                description = "Closed case or custody event concurrency conflict",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "500",
                description =
                        "Evidence file unavailable, storage failure, or custody event persistence failure; never a non-conforming result",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemDetail.class),
                                examples =
                                        @ExampleObject(
                                                name = "Stored file technically unreadable",
                                                value = OperationalCustodyExamples.FILE_UNAVAILABLE)))
    })
    public ResponseEntity<IntegrityVerificationResponse> verifyIntegrity(
            @Parameter(description = "Digital evidence identifier", example = "6f674949-c508-49bf-a160-ef720f9b51ee")
                    @PathVariable
                    UUID evidenceId,
            @AuthenticationPrincipal AuthenticatedOperator actor) {
        IntegrityVerificationResponse response = service.verify(evidenceId, actor);
        return ResponseEntity.ok()
                .location(responses.location(response.eventSummary()))
                .body(response);
    }
}
