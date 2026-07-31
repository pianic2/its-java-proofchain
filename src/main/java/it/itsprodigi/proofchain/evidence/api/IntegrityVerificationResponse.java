package it.itsprodigi.proofchain.evidence.api;

import io.swagger.v3.oas.annotations.media.Schema;
import it.itsprodigi.proofchain.custodyevent.api.CustodyEventSummaryResponse;
import java.time.Instant;
import java.util.UUID;

/**
 * Completed result of one file-integrity verification.
 *
 * <p>{@code valid} is {@code true} only when the recomputed digest and the actually observed byte count both match the
 * persisted evidence metadata. Both outcomes are successful completions, so an invalid result is reported here and
 * never as a Problem Detail. Expected and actual sizes are exposed side by side so a size-only mismatch is diagnosable
 * without changing the frozen custody-event payload.
 *
 * <p>Storage keys, absolute paths, chain head and count, and the optimistic version are never exposed.
 */
@Schema(
        description =
                "Result of one file-integrity verification: the recomputed digest and observed byte count compared against the persisted evidence metadata, plus the appended custody event summary.")
public record IntegrityVerificationResponse(
        @Schema(description = "Verified digital evidence identifier")
        UUID evidenceId,

        @Schema(description = "True only when both the recomputed digest and the observed byte count match")
        boolean valid,

        @Schema(description = "Persisted content SHA-256 of the evidence")
        String expectedContentSha256,

        @Schema(description = "SHA-256 recomputed over the exact stored bytes during this verification")
        String actualContentSha256,

        @Schema(description = "Persisted file size in bytes")
        long expectedFileSize,

        @Schema(description = "Number of bytes actually read during this verification")
        long actualFileSize,

        @Schema(description = "Single server instant shared by the evidence update and the appended custody event")
        Instant verifiedAt,

        @Schema(description = "Summary of the appended INTEGRITY_VERIFIED custody event, without its payload")
        CustodyEventSummaryResponse eventSummary) {}
