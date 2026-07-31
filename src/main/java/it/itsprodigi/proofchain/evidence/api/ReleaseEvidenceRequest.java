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
 * Strict evidence release command document.
 *
 * <p>The caller supplies only the operational reason. Sprint 5 models no recipient, external organization or release
 * document: custody simply ends, and the previous holder is read from the locked aggregate.
 */
@JsonDeserialize(using = ReleaseEvidenceRequest.Deserializer.class)
@Schema(
        name = "ReleaseEvidenceRequest",
        description = "Evidence release command. Unknown properties are rejected.",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ReleaseEvidenceRequest(
        @NotBlank
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                nullable = false,
                minLength = 1,
                maxLength = 1000,
                example = "Proceedings closed; custody of the evidence is terminated.",
                description = "Operational reason; trimmed and validated to 1 to 1000 characters after trimming.")
        String reason) {

    static final class Deserializer extends StdDeserializer<ReleaseEvidenceRequest> {

        private static final Set<String> FIELDS = Set.of("reason");

        Deserializer() {
            super(ReleaseEvidenceRequest.class);
        }

        @Override
        public ReleaseEvidenceRequest deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode root = StrictEvidenceJson.object(parser, context, ReleaseEvidenceRequest.class, FIELDS);
            return new ReleaseEvidenceRequest(
                    StrictEvidenceJson.nullableString(root, "reason", context, ReleaseEvidenceRequest.class));
        }
    }
}
