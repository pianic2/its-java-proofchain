package it.itsprodigi.proofchain.custodycase.api;

import io.swagger.v3.oas.annotations.media.Schema;
import it.itsprodigi.proofchain.custodycase.domain.CasePriority;
import java.util.EnumSet;
import java.util.Set;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.std.StdDeserializer;

@JsonDeserialize(using = PatchCaseMetadataRequest.Deserializer.class)
@Schema(
        name = "PatchCaseMetadataRequest",
        description =
                "Partial custody case metadata update. At least one property is required and unknown properties are rejected.",
        minProperties = 1,
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public final class PatchCaseMetadataRequest {

    private final String title;
    private final String description;
    private final String authorityName;
    private final String externalReference;
    private final String location;
    private final CasePriority priority;
    private final EnumSet<Field> presentFields;

    private PatchCaseMetadataRequest(
            String title,
            String description,
            String authorityName,
            String externalReference,
            String location,
            CasePriority priority,
            EnumSet<Field> presentFields) {
        this.title = title;
        this.description = description;
        this.authorityName = authorityName;
        this.externalReference = externalReference;
        this.location = location;
        this.priority = priority;
        this.presentFields = presentFields.clone();
    }

    @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = false,
            minLength = 3,
            maxLength = 200,
            example = "Mobile device seizure - supplemental examination",
            description = "When present, must be non-null and is validated after trimming.")
    public String getTitle() {
        return title;
    }

    @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            types = {"string", "null"},
            maxLength = 2000,
            example = "Supplemental examination approved.",
            description = "Explicit null or blank clears the value; length is validated after trimming.")
    public String getDescription() {
        return description;
    }

    @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            types = {"string", "null"},
            maxLength = 200,
            example = "Court of Rome",
            description = "Explicit null or blank clears the value; length is validated after trimming.")
    public String getAuthorityName() {
        return authorityName;
    }

    @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            types = {"string", "null"},
            maxLength = 200,
            example = "WARRANT-2026-0142-A",
            description = "Explicit null or blank clears the value; length is validated after trimming.")
    public String getExternalReference() {
        return externalReference;
    }

    @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            types = {"string", "null"},
            maxLength = 300,
            example = "Evidence room B",
            description = "Explicit null or blank clears the value; length is validated after trimming.")
    public String getLocation() {
        return location;
    }

    @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = false,
            example = "CRITICAL",
            description = "When present, must be non-null.")
    public CasePriority getPriority() {
        return priority;
    }

    public boolean hasTitle() {
        return presentFields.contains(Field.TITLE);
    }

    public boolean hasDescription() {
        return presentFields.contains(Field.DESCRIPTION);
    }

    public boolean hasAuthorityName() {
        return presentFields.contains(Field.AUTHORITY_NAME);
    }

    public boolean hasExternalReference() {
        return presentFields.contains(Field.EXTERNAL_REFERENCE);
    }

    public boolean hasLocation() {
        return presentFields.contains(Field.LOCATION);
    }

    public boolean hasPriority() {
        return presentFields.contains(Field.PRIORITY);
    }

    public boolean hasAnyField() {
        return !presentFields.isEmpty();
    }

    private enum Field {
        TITLE,
        DESCRIPTION,
        AUTHORITY_NAME,
        EXTERNAL_REFERENCE,
        LOCATION,
        PRIORITY
    }

    static final class Deserializer extends StdDeserializer<PatchCaseMetadataRequest> {

        private static final Set<String> FIELDS =
                Set.of("title", "description", "authorityName", "externalReference", "location", "priority");

        Deserializer() {
            super(PatchCaseMetadataRequest.class);
        }

        @Override
        public PatchCaseMetadataRequest deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode root = StrictCaseJson.object(parser, context, PatchCaseMetadataRequest.class, FIELDS);
            EnumSet<Field> present = EnumSet.noneOf(Field.class);
            if (root.has("title")) present.add(Field.TITLE);
            if (root.has("description")) present.add(Field.DESCRIPTION);
            if (root.has("authorityName")) present.add(Field.AUTHORITY_NAME);
            if (root.has("externalReference")) present.add(Field.EXTERNAL_REFERENCE);
            if (root.has("location")) present.add(Field.LOCATION);
            if (root.has("priority")) present.add(Field.PRIORITY);
            return new PatchCaseMetadataRequest(
                    StrictCaseJson.nullableString(root, "title", context, PatchCaseMetadataRequest.class),
                    StrictCaseJson.nullableString(root, "description", context, PatchCaseMetadataRequest.class),
                    StrictCaseJson.nullableString(root, "authorityName", context, PatchCaseMetadataRequest.class),
                    StrictCaseJson.nullableString(root, "externalReference", context, PatchCaseMetadataRequest.class),
                    StrictCaseJson.nullableString(root, "location", context, PatchCaseMetadataRequest.class),
                    StrictCaseJson.nullableEnum(
                            root, "priority", CasePriority.class, context, PatchCaseMetadataRequest.class),
                    present);
        }
    }
}
