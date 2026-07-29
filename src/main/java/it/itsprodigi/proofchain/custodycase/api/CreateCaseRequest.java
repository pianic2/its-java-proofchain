package it.itsprodigi.proofchain.custodycase.api;

import io.swagger.v3.oas.annotations.media.Schema;
import it.itsprodigi.proofchain.custodycase.domain.CasePriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Set;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.std.StdDeserializer;

@JsonDeserialize(using = CreateCaseRequest.Deserializer.class)
@Schema(
        name = "CreateCaseRequest",
        description = "Metadata for a new custody case. Unknown properties are rejected.",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record CreateCaseRequest(
        @NotBlank
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                minLength = 3,
                maxLength = 200,
                example = "Mobile device seizure",
                description = "Title; validated after trimming leading and trailing whitespace.")
        String title,

        @Schema(
                requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                types = {"string", "null"},
                maxLength = 2000,
                example = "Device collected under warrant 2026-0142.",
                description =
                        "Optional description; blank values normalize to null and length is validated after trimming.")
        String description,

        @Schema(
                requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                types = {"string", "null"},
                maxLength = 200,
                example = "Court of Rome",
                description =
                        "Optional authority name; blank values normalize to null and length is validated after trimming.")
        String authorityName,

        @Schema(
                requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                types = {"string", "null"},
                maxLength = 200,
                example = "WARRANT-2026-0142",
                description =
                        "Optional external reference; blank values normalize to null and length is validated after trimming.")
        String externalReference,

        @Schema(
                requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                types = {"string", "null"},
                maxLength = 300,
                example = "Evidence room A",
                description =
                        "Optional location; blank values normalize to null and length is validated after trimming.")
        String location,

        @NotNull
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                nullable = false,
                example = "HIGH",
                description = "Initial case priority.")
        CasePriority priority) {

    static final class Deserializer extends StdDeserializer<CreateCaseRequest> {

        private static final Set<String> FIELDS =
                Set.of("title", "description", "authorityName", "externalReference", "location", "priority");

        Deserializer() {
            super(CreateCaseRequest.class);
        }

        @Override
        public CreateCaseRequest deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode root = StrictCaseJson.object(parser, context, CreateCaseRequest.class, FIELDS);
            return new CreateCaseRequest(
                    StrictCaseJson.nullableString(root, "title", context, CreateCaseRequest.class),
                    StrictCaseJson.nullableString(root, "description", context, CreateCaseRequest.class),
                    StrictCaseJson.nullableString(root, "authorityName", context, CreateCaseRequest.class),
                    StrictCaseJson.nullableString(root, "externalReference", context, CreateCaseRequest.class),
                    StrictCaseJson.nullableString(root, "location", context, CreateCaseRequest.class),
                    StrictCaseJson.nullableEnum(
                            root, "priority", CasePriority.class, context, CreateCaseRequest.class));
        }
    }
}
