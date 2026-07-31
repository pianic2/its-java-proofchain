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
        @Schema(
                requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                types = {"string", "null"},
                maxLength = 64,
                pattern = "[A-Z0-9][A-Z0-9._-]{0,63}",
                example = "PHONE-2026-0042",
                description =
                        "Optional per-case reference tag; trimmed, uppercased and validated after normalization. Blank values become null.")
        String referenceTag,

        @NotBlank
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                minLength = 3,
                maxLength = 200,
                example = "Forensic mobile image",
                description = "Evidence title; validated after trimming leading and trailing whitespace.")
        String title,

        @Schema(
                requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                types = {"string", "null"},
                maxLength = 2000,
                example = "Full logical acquisition of the seized handset.",
                description = "Optional description; trimmed and normalized from blank to null.")
        String description,

        @NotNull
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "DEVICE", description = "Typed source category.")
        SourceType sourceType,

        @Schema(
                requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                types = {"string", "null"},
                maxLength = 500,
                example = "Seized Android handset",
                description = "Optional source description; trimmed and normalized from blank to null.")
        String sourceDescription,

        @Schema(
                requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                types = {"string", "null"},
                maxLength = 100,
                example = "Example Mobile",
                description = "Optional source manufacturer; trimmed and normalized from blank to null.")
        String sourceManufacturer,

        @Schema(
                requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                types = {"string", "null"},
                maxLength = 100,
                example = "Model X",
                description = "Optional source model; trimmed and normalized from blank to null.")
        String sourceModel,

        @Schema(
                requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                types = {"string", "null"},
                maxLength = 200,
                example = "SN-000042",
                description = "Optional source serial number; trimmed and normalized from blank to null.")
        String sourceSerialNumber,

        @Schema(
                requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                types = {"string", "null"},
                maxLength = 300,
                example = "device:userdata",
                description = "Optional logical source identifier; trimmed and normalized from blank to null.")
        String sourceLogicalIdentifier,

        @NotNull
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "LOGICAL",
                description = "Typed acquisition method.")
        AcquisitionMethod acquisitionMethod,

        @Schema(
                requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                types = {"string", "null"},
                format = "date-time",
                example = "2026-07-29T09:30:00Z",
                description = "Optional acquisition instant; it cannot be later than evidence creation.")
        Instant acquiredAt,

        @Schema(
                requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                types = {"string", "null"},
                maxLength = 300,
                example = "Forensics laboratory A",
                description = "Optional acquisition location; trimmed and normalized from blank to null.")
        String acquisitionLocation,

        @Schema(
                requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                types = {"string", "null"},
                maxLength = 200,
                example = "Example Extractor",
                description = "Optional acquisition tool name; trimmed and normalized from blank to null.")
        String acquisitionToolName,

        @Schema(
                requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                types = {"string", "null"},
                maxLength = 100,
                example = "1.2.3",
                description = "Optional acquisition tool version; trimmed and normalized from blank to null.")
        String acquisitionToolVersion,

        @Schema(
                requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                types = {"string", "null"},
                maxLength = 2000,
                example = "Airplane mode enabled before acquisition.",
                description = "Optional acquisition notes; trimmed and normalized from blank to null.")
        String acquisitionNotes,

        @NotNull
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                format = "uuid",
                example = "b32ecaa9-8c4c-43d7-bdc0-28f9e38f3c37",
                description = "Active eligible case member who receives initial custody.")
        UUID initialHolderId) {

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
