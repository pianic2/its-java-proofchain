package it.itsprodigi.proofchain.evidence.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.Set;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.std.StdDeserializer;

/**
 * Strict evidence seal command document.
 *
 * <p>The caller supplies only the operational reason. Actor, holder, status, event type, timestamp and event payload
 * are always derived server side from the locked aggregate.
 */
@JsonDeserialize(using = SealEvidenceRequest.Deserializer.class)
@Schema(
        name = "SealEvidenceRequest",
        description = "Evidence seal command. Unknown properties are rejected.",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SealEvidenceRequest(
        @NotBlank
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                nullable = false,
                minLength = 1,
                maxLength = 1000,
                example = "Analysis completed; the working copy is sealed for preservation.",
                description = "Operational reason; trimmed and validated to 1 to 1000 characters after trimming.")
        String reason) {

    static final class Deserializer extends StdDeserializer<SealEvidenceRequest> {

        private static final Set<String> FIELDS = Set.of("reason");

        Deserializer() {
            super(SealEvidenceRequest.class);
        }

        @Override
        public SealEvidenceRequest deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode root = StrictEvidenceJson.object(parser, context, SealEvidenceRequest.class, FIELDS);
            return new SealEvidenceRequest(
                    StrictEvidenceJson.nullableString(root, "reason", context, SealEvidenceRequest.class));
        }
    }
}
