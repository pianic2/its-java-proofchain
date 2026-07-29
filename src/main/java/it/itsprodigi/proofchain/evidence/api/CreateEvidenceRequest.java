package it.itsprodigi.proofchain.evidence.api;

import io.swagger.v3.oas.annotations.media.Schema;
import it.itsprodigi.proofchain.evidence.domain.AcquisitionMethod;
import it.itsprodigi.proofchain.evidence.domain.SourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.std.StdDeserializer;

@JsonDeserialize(using = CreateEvidenceRequest.Deserializer.class)
@Schema(
        name = "CreateEvidenceRequest",
        description = "Strict metadata document for a new digital evidence upload.",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record CreateEvidenceRequest(
        @Schema(maxLength = 64) String referenceTag,
        @NotBlank @Schema(minLength = 3, maxLength = 200) String title,
        @Schema(maxLength = 2000) String description,
        @NotNull SourceType sourceType,
        @Schema(maxLength = 500) String sourceDescription,
        @Schema(maxLength = 100) String sourceManufacturer,
        @Schema(maxLength = 100) String sourceModel,
        @Schema(maxLength = 200) String sourceSerialNumber,
        @Schema(maxLength = 300) String sourceLogicalIdentifier,
        @NotNull AcquisitionMethod acquisitionMethod,
        Instant acquiredAt,
        @Schema(maxLength = 300) String acquisitionLocation,
        @Schema(maxLength = 200) String acquisitionToolName,
        @Schema(maxLength = 100) String acquisitionToolVersion,
        @Schema(maxLength = 2000) String acquisitionNotes,
        @NotNull UUID initialHolderId) {

    static final class Deserializer extends StdDeserializer<CreateEvidenceRequest> {

        private static final Set<String> FIELDS = Set.of(
                "referenceTag",
                "title",
                "description",
                "sourceType",
                "sourceDescription",
                "sourceManufacturer",
                "sourceModel",
                "sourceSerialNumber",
                "sourceLogicalIdentifier",
                "acquisitionMethod",
                "acquiredAt",
                "acquisitionLocation",
                "acquisitionToolName",
                "acquisitionToolVersion",
                "acquisitionNotes",
                "initialHolderId");

        Deserializer() {
            super(CreateEvidenceRequest.class);
        }

        @Override
        public CreateEvidenceRequest deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode root = StrictEvidenceJson.object(parser, context, CreateEvidenceRequest.class, FIELDS);
            return new CreateEvidenceRequest(
                    StrictEvidenceJson.nullableString(root, "referenceTag", context, CreateEvidenceRequest.class),
                    StrictEvidenceJson.nullableString(root, "title", context, CreateEvidenceRequest.class),
                    StrictEvidenceJson.nullableString(root, "description", context, CreateEvidenceRequest.class),
                    StrictEvidenceJson.nullableEnum(
                            root, "sourceType", SourceType.class, context, CreateEvidenceRequest.class),
                    StrictEvidenceJson.nullableString(root, "sourceDescription", context, CreateEvidenceRequest.class),
                    StrictEvidenceJson.nullableString(root, "sourceManufacturer", context, CreateEvidenceRequest.class),
                    StrictEvidenceJson.nullableString(root, "sourceModel", context, CreateEvidenceRequest.class),
                    StrictEvidenceJson.nullableString(root, "sourceSerialNumber", context, CreateEvidenceRequest.class),
                    StrictEvidenceJson.nullableString(
                            root, "sourceLogicalIdentifier", context, CreateEvidenceRequest.class),
                    StrictEvidenceJson.nullableEnum(
                            root, "acquisitionMethod", AcquisitionMethod.class, context, CreateEvidenceRequest.class),
                    StrictEvidenceJson.nullableInstant(root, "acquiredAt", context, CreateEvidenceRequest.class),
                    StrictEvidenceJson.nullableString(
                            root, "acquisitionLocation", context, CreateEvidenceRequest.class),
                    StrictEvidenceJson.nullableString(
                            root, "acquisitionToolName", context, CreateEvidenceRequest.class),
                    StrictEvidenceJson.nullableString(
                            root, "acquisitionToolVersion", context, CreateEvidenceRequest.class),
                    StrictEvidenceJson.nullableString(root, "acquisitionNotes", context, CreateEvidenceRequest.class),
                    StrictEvidenceJson.nullableUuid(root, "initialHolderId", context, CreateEvidenceRequest.class));
        }
    }
}
