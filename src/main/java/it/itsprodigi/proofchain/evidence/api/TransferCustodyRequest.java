package it.itsprodigi.proofchain.evidence.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.std.StdDeserializer;

/**
 * Strict custody transfer command document.
 *
 * <p>The caller supplies only the target holder and the operational reason. Actor, previous holder, event type,
 * timestamp, status and event payload are always derived server side from the locked aggregate.
 */
@JsonDeserialize(using = TransferCustodyRequest.Deserializer.class)
@Schema(
        name = "TransferCustodyRequest",
        description = "Custody transfer command. Unknown properties are rejected.",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record TransferCustodyRequest(
        @NotNull
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                nullable = false,
                format = "uuid",
                example = "b32ecaa9-8c4c-43d7-bdc0-28f9e38f3c37",
                description =
                        "Eligible member of the owning custody case that receives custody. It must differ from the current holder.")
        UUID newHolderId,

        @NotBlank
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                nullable = false,
                minLength = 1,
                maxLength = 1000,
                example = "Handover to the laboratory analyst.",
                description = "Operational reason; trimmed and validated to 1 to 1000 characters after trimming.")
        String reason) {

    static final class Deserializer extends StdDeserializer<TransferCustodyRequest> {

        private static final Set<String> FIELDS = Set.of("newHolderId", "reason");

        Deserializer() {
            super(TransferCustodyRequest.class);
        }

        @Override
        public TransferCustodyRequest deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode root = StrictEvidenceJson.object(parser, context, TransferCustodyRequest.class, FIELDS);
            return new TransferCustodyRequest(
                    StrictEvidenceJson.nullableUuid(root, "newHolderId", context, TransferCustodyRequest.class),
                    StrictEvidenceJson.nullableString(root, "reason", context, TransferCustodyRequest.class));
        }
    }
}
