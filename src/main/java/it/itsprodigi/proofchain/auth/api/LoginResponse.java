package it.itsprodigi.proofchain.auth.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(name = "LoginResponse", description = "Issued bearer access token and its absolute expiry.")
public record LoginResponse(
        /*
         * The example is a redaction marker rather than a decodable token: an example that parses as a JWT would be
         * copied out of Swagger UI and replayed, and the contract must not publish anything token-shaped.
         */
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "<redacted-access-token>",
                description = "Signed JWT access token. The example is a redaction marker, not a decodable token.")
        String accessToken,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Bearer", description = "Always Bearer.")
        String tokenType,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                format = "date-time",
                example = "2026-07-29T09:00:00Z",
                description = "Absolute UTC expiry of the issued token.")
        Instant expiresAt,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "1800",
                description = "Remaining lifetime in seconds at issue time.")
        long expiresInSeconds) {}
