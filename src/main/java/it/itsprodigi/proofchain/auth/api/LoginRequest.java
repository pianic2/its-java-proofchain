package it.itsprodigi.proofchain.auth.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(name = "LoginRequest", description = "Operator credentials. Both values are required and never logged.")
public record LoginRequest(
        @NotBlank
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "case.manager",
                description = "Operator username; normalized to lowercase before lookup.")
        String username,

        /*
         * The example is a redaction marker on purpose. A syntactically plausible password would be copied verbatim out
         * of Swagger UI into real deployments, and the published contract must never suggest a usable credential.
         */
        @NotBlank
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                format = "password",
                example = "<redacted>",
                description = "Operator password. Never rendered, logged or echoed; the example is a redaction marker.")
        String password) {}
