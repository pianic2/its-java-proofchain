package it.itsprodigi.proofchain.evidence.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.evidence.application.EvidenceRegistrationService;
import it.itsprodigi.proofchain.evidence.application.EvidenceRequestValidationException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/cases/{caseId}/evidences")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Digital evidence", description = "Digital evidence registration APIs.")
public class EvidenceController {

    private static final Set<String> REQUIRED_PARTS = Set.of("metadata", "file");

    private final EvidenceRegistrationService service;

    public EvidenceController(EvidenceRegistrationService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            operationId = "registerDigitalEvidence",
            summary = "Register digital evidence",
            description =
                    "Atomically stages file bytes, registers evidence metadata in an OPEN custody case, and finalizes storage. The multipart request must contain exactly metadata (application/json) and file (binary).")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Digital evidence registered",
                headers =
                        @Header(name = "Location", required = true, schema = @Schema(type = "string", format = "uri")),
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = EvidenceResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description =
                        "Invalid metadata, multipart structure, filename, media type fallback input, or empty file",
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
                description = "Visible case cannot receive evidence from the caller or holder selection is forbidden",
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
                description = "Closed case, ineligible holder, or duplicate reference tag",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "413",
                description = "Evidence file exceeds the configured limit",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "500",
                description = "Evidence storage failure",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<EvidenceResponse> register(
            @Parameter(description = "Custody case identifier") @PathVariable UUID caseId,
            @Valid @RequestPart("metadata") CreateEvidenceRequest metadata,
            @Parameter(description = "Evidence content", schema = @Schema(type = "string", format = "binary"))
                    @RequestPart("file")
                    MultipartFile file,
            HttpServletRequest servletRequest,
            @AuthenticationPrincipal AuthenticatedOperator actor) {
        requireExactParts(servletRequest);
        EvidenceResponse response = service.register(caseId, metadata, file, actor);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/evidences/{evidenceId}")
                .buildAndExpand(response.id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    private static void requireExactParts(HttpServletRequest request) {
        try {
            List<Part> servletParts = request.getParts().stream().toList();
            List<String> names = servletParts.stream().map(Part::getName).toList();
            if (names.size() != 2 || !Set.copyOf(names).equals(REQUIRED_PARTS)) {
                throw new EvidenceRequestValidationException();
            }
            String metadataContentType = request.getPart("metadata").getContentType();
            MediaType metadataType = MediaType.parseMediaType(metadataContentType);
            if (!metadataType.getType().equals(MediaType.APPLICATION_JSON.getType())
                    || !metadataType.getSubtype().equals(MediaType.APPLICATION_JSON.getSubtype())
                    || metadataType.getParameters().keySet().stream()
                            .anyMatch(parameter -> !parameter.equals("charset"))) {
                throw new EvidenceRequestValidationException();
            }
        } catch (IOException | ServletException | IllegalArgumentException exception) {
            throw new EvidenceRequestValidationException();
        }
    }
}
