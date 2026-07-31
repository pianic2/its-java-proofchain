package it.itsprodigi.proofchain.evidence.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.itsprodigi.proofchain.auth.security.AuthenticatedOperator;
import it.itsprodigi.proofchain.evidence.application.EvidenceDownloadDescriptor;
import it.itsprodigi.proofchain.evidence.application.EvidenceQueryService;
import it.itsprodigi.proofchain.evidence.application.OpenedEvidence;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Digital evidence", description = "Digital evidence registration, metadata and content APIs.")
public class EvidenceReadController {

    private static final MediaType DEFAULT_MEDIA_TYPE = MediaType.APPLICATION_OCTET_STREAM;

    private final EvidenceQueryService service;

    public EvidenceReadController(EvidenceQueryService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/cases/{caseId}/evidences")
    @Operation(
            operationId = "listDigitalEvidence",
            summary = "List evidence in a visible custody case",
            description =
                    "Available to ADMIN callers and every assigned case member, including AUDITOR. Closed cases remain readable. Paging defaults to page 0 and size 20, with a maximum size of 100. Results are always ordered by createdAt descending and id ascending; client sorting and evidence filters are not supported.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Visible evidence page",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = EvidencePageResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Invalid pagination, case identifier, or unsupported sorting",
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
    public EvidencePageResponse list(
            @Parameter(description = "Custody case identifier", example = "1ca01c67-75b9-48e3-a2ed-72259373c67c")
                    @PathVariable
                    UUID caseId,
            @Parameter(description = "Zero-based page index", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size from 1 to 100", example = "20") @RequestParam(defaultValue = "20")
                    int size,
            @Parameter(description = "Unsupported; any occurrence is rejected") @RequestParam(required = false)
                    String sort,
            HttpServletRequest request,
            @AuthenticationPrincipal AuthenticatedOperator actor) {
        String[] sortValues = request.getParameterValues("sort");
        List<String> requestedSort = sortValues == null ? List.of() : Arrays.asList(sortValues);
        return service.list(caseId, page, size, requestedSort, actor);
    }

    @GetMapping("/api/v1/evidences/{evidenceId}")
    @Operation(
            operationId = "getDigitalEvidence",
            summary = "Get visible evidence details",
            description =
                    "Returns complete evidence metadata to ADMIN callers or any assigned member of its custody case. Closed cases and RELEASED evidence remain readable. Existing but inaccessible evidence is indistinguishable from missing evidence; storage keys and persistence versions are never returned.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Visible evidence details",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = EvidenceResponse.class))),
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
                                schema = @Schema(implementation = ProblemDetail.class)))
    })
    public EvidenceResponse get(
            @Parameter(description = "Digital evidence identifier", example = "6f674949-c508-49bf-a160-ef720f9b51ee")
                    @PathVariable
                    UUID evidenceId,
            @AuthenticationPrincipal AuthenticatedOperator actor) {
        return service.get(evidenceId, actor);
    }

    @GetMapping("/api/v1/evidences/{evidenceId}/download")
    @Operation(
            operationId = "downloadDigitalEvidence",
            summary = "Download visible evidence content",
            description =
                    "Streams the exact stored bytes as an attachment outside the database transaction. Available to ADMIN callers and any assigned case member for IN_CUSTODY, SEALED or RELEASED evidence, including closed cases. Range is ignored and every success is a complete 200 response; ETag, conditional requests and implicit integrity verification are not supported.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Complete evidence content",
                headers = {
                    @Header(name = HttpHeaders.CONTENT_DISPOSITION, required = true, schema = @Schema(type = "string")),
                    @Header(
                            name = HttpHeaders.CONTENT_LENGTH,
                            required = true,
                            schema = @Schema(type = "integer", format = "int64"))
                },
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                                schema = @Schema(type = "string", format = "binary"))),
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
                responseCode = "500",
                description = "Evidence file unavailable or storage failure",
                content =
                        @Content(
                                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<InputStreamResource> download(
            @Parameter(description = "Digital evidence identifier", example = "6f674949-c508-49bf-a160-ef720f9b51ee")
                    @PathVariable
                    UUID evidenceId,
            @Parameter(
                            name = HttpHeaders.RANGE,
                            in = ParameterIn.HEADER,
                            description = "Ignored; the complete representation is always returned",
                            required = false)
                    @RequestHeader(name = HttpHeaders.RANGE, required = false)
                    String range,
            @AuthenticationPrincipal AuthenticatedOperator actor) {
        EvidenceDownloadDescriptor descriptor = service.prepareDownload(evidenceId, actor);
        MediaType mediaType = responseMediaType(descriptor.mediaType());
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(safeFilename(descriptor), StandardCharsets.UTF_8)
                .build();
        OpenedEvidence opened = service.openDownload(descriptor);
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(descriptor.fileSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new InputStreamResource(opened.content()));
    }

    static MediaType responseMediaType(String storedMediaType) {
        if (storedMediaType == null || storedMediaType.length() > 255) {
            return DEFAULT_MEDIA_TYPE;
        }
        try {
            MediaType mediaType = MediaType.parseMediaType(storedMediaType);
            return mediaType.isWildcardType() || mediaType.isWildcardSubtype() ? DEFAULT_MEDIA_TYPE : mediaType;
        } catch (IllegalArgumentException exception) {
            return DEFAULT_MEDIA_TYPE;
        }
    }

    static String safeFilename(EvidenceDownloadDescriptor descriptor) {
        String candidate = descriptor.originalFilename().strip();
        int separator = Math.max(candidate.lastIndexOf('/'), candidate.lastIndexOf('\\'));
        String basename = candidate.substring(separator + 1).strip();
        StringBuilder sanitized = new StringBuilder(basename.length());
        basename.codePoints()
                .forEach(codePoint -> sanitized.appendCodePoint(Character.isISOControl(codePoint) ? '_' : codePoint));
        String filename = sanitized.toString();
        if (filename.isBlank() || filename.equals(".") || filename.equals("..") || filename.length() > 255) {
            return "evidence-" + descriptor.evidenceId() + ".bin";
        }
        return filename;
    }
}
