package it.itsprodigi.proofchain.evidence.api;

import io.swagger.v3.oas.annotations.media.Schema;
import it.itsprodigi.proofchain.evidence.domain.AcquisitionMethod;
import it.itsprodigi.proofchain.evidence.domain.SourceType;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.std.StdDeserializer;

/**
 * Strict presence-aware descriptive metadata patch document.
 *
 * <p>The document carries exactly the fourteen modifiable descriptive fields plus the required operational reason. Every
 * other evidence property, including {@code referenceTag}, lifecycle, holder, file metadata, hashes, storage data, chain
 * internals and the optimistic version, is immutable through this command and is rejected as an unknown property by
 * {@link Deserializer}.
 *
 * <p>Field <em>presence</em> is tracked separately from the field value with an {@link EnumSet} filled from the parsed
 * JSON object, exactly like {@code PatchCaseMetadataRequest}. A record with nullable components cannot express the
 * difference between "absent" and "explicitly null", and that difference is the whole contract: an absent field
 * preserves the current aggregate value while an explicit {@code null} clears an optional one.
 */
@JsonDeserialize(using = PatchEvidenceMetadataRequest.Deserializer.class)
@Schema(
        name = "PatchEvidenceMetadataRequest",
        description =
                "Presence-aware descriptive metadata patch. An absent property preserves the current value, an explicit null clears an optional value, and unknown or immutable properties are rejected.",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public final class PatchEvidenceMetadataRequest {

    private final String title;
    private final String description;
    private final SourceType sourceType;
    private final String sourceDescription;
    private final String sourceManufacturer;
    private final String sourceModel;
    private final String sourceSerialNumber;
    private final String sourceLogicalIdentifier;
    private final AcquisitionMethod acquisitionMethod;
    private final Instant acquiredAt;
    private final String acquisitionLocation;
    private final String acquisitionToolName;
    private final String acquisitionToolVersion;
    private final String acquisitionNotes;
    private final String reason;
    private final EnumSet<Field> presentFields;

    private PatchEvidenceMetadataRequest(
            String title,
            String description,
            SourceType sourceType,
            String sourceDescription,
            String sourceManufacturer,
            String sourceModel,
            String sourceSerialNumber,
            String sourceLogicalIdentifier,
            AcquisitionMethod acquisitionMethod,
            Instant acquiredAt,
            String acquisitionLocation,
            String acquisitionToolName,
            String acquisitionToolVersion,
            String acquisitionNotes,
            String reason,
            EnumSet<Field> presentFields) {
        this.title = title;
        this.description = description;
        this.sourceType = sourceType;
        this.sourceDescription = sourceDescription;
        this.sourceManufacturer = sourceManufacturer;
        this.sourceModel = sourceModel;
        this.sourceSerialNumber = sourceSerialNumber;
        this.sourceLogicalIdentifier = sourceLogicalIdentifier;
        this.acquisitionMethod = acquisitionMethod;
        this.acquiredAt = acquiredAt;
        this.acquisitionLocation = acquisitionLocation;
        this.acquisitionToolName = acquisitionToolName;
        this.acquisitionToolVersion = acquisitionToolVersion;
        this.acquisitionNotes = acquisitionNotes;
        this.reason = reason;
        this.presentFields = presentFields.clone();
    }

    /** The exactly fourteen descriptive fields this command may change. */
    public enum Field {
        TITLE,
        DESCRIPTION,
        SOURCE_TYPE,
        SOURCE_DESCRIPTION,
        SOURCE_MANUFACTURER,
        SOURCE_MODEL,
        SOURCE_SERIAL_NUMBER,
        SOURCE_LOGICAL_IDENTIFIER,
        ACQUISITION_METHOD,
        ACQUIRED_AT,
        ACQUISITION_LOCATION,
        ACQUISITION_TOOL_NAME,
        ACQUISITION_TOOL_VERSION,
        ACQUISITION_NOTES
    }

    /** True only when the JSON document actually carried the property, whatever its value was. */
    public boolean has(Field field) {
        Objects.requireNonNull(field, "field must not be null");
        return presentFields.contains(field);
    }

    @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = false,
            minLength = 3,
            maxLength = 200,
            example = "Forensic disk image of the seized laptop",
            description = "When present it must be non-null and non-blank; length is validated after trimming.")
    public String getTitle() {
        return title;
    }

    @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            types = {"string", "null"},
            maxLength = 2000,
            example = "Full physical acquisition of the internal drive.",
            description = "Explicit null or blank clears the value; length is validated after trimming.")
    public String getDescription() {
        return description;
    }

    @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = false,
            example = "DEVICE",
            description = "When present it must be a supported non-null source type.")
    public SourceType getSourceType() {
        return sourceType;
    }

    @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            types = {"string", "null"},
            maxLength = 500,
            example = "Laptop seized in the living room.",
            description = "Explicit null or blank clears the value; length is validated after trimming.")
    public String getSourceDescription() {
        return sourceDescription;
    }

    @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            types = {"string", "null"},
            maxLength = 100,
            example = "ACME",
            description = "Explicit null or blank clears the value; length is validated after trimming.")
    public String getSourceManufacturer() {
        return sourceManufacturer;
    }

    @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            types = {"string", "null"},
            maxLength = 100,
            example = "X1-2026",
            description = "Explicit null or blank clears the value; length is validated after trimming.")
    public String getSourceModel() {
        return sourceModel;
    }

    @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            types = {"string", "null"},
            maxLength = 200,
            example = "SN-0042-AB",
            description = "Explicit null or blank clears the value; length is validated after trimming.")
    public String getSourceSerialNumber() {
        return sourceSerialNumber;
    }

    @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            types = {"string", "null"},
            maxLength = 300,
            example = "host-42/volume-1",
            description = "Explicit null or blank clears the value; length is validated after trimming.")
    public String getSourceLogicalIdentifier() {
        return sourceLogicalIdentifier;
    }

    @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = false,
            example = "PHYSICAL",
            description = "When present it must be a supported non-null acquisition method.")
    public AcquisitionMethod getAcquisitionMethod() {
        return acquisitionMethod;
    }

    @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            types = {"string", "null"},
            format = "date-time",
            example = "2026-07-30T09:15:00Z",
            description =
                    "Explicit null clears the value; when non-null it is truncated to microseconds and must not be later than the immutable evidence createdAt.")
    public Instant getAcquiredAt() {
        return acquiredAt;
    }

    @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            types = {"string", "null"},
            maxLength = 300,
            example = "Evidence room B",
            description = "Explicit null or blank clears the value; length is validated after trimming.")
    public String getAcquisitionLocation() {
        return acquisitionLocation;
    }

    @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            types = {"string", "null"},
            maxLength = 200,
            example = "AcquireTool",
            description = "Explicit null or blank clears the value; length is validated after trimming.")
    public String getAcquisitionToolName() {
        return acquisitionToolName;
    }

    @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            types = {"string", "null"},
            maxLength = 100,
            example = "3.1.4",
            description = "Explicit null or blank clears the value; length is validated after trimming.")
    public String getAcquisitionToolVersion() {
        return acquisitionToolVersion;
    }

    @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            types = {"string", "null"},
            maxLength = 2000,
            example = "Write blocker used during acquisition.",
            description = "Explicit null or blank clears the value; length is validated after trimming.")
    public String getAcquisitionNotes() {
        return acquisitionNotes;
    }

    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = false,
            minLength = 1,
            maxLength = 1000,
            example = "Corrected the acquisition tool version after the laboratory review.",
            description =
                    "Operational reason; trimmed and validated to 1 to 1000 characters after trimming. It is never part of the before and after metadata snapshots.")
    public String getReason() {
        return reason;
    }

    static final class Deserializer extends StdDeserializer<PatchEvidenceMetadataRequest> {

        private static final Set<String> FIELDS = Set.of(
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
                "reason");

        Deserializer() {
            super(PatchEvidenceMetadataRequest.class);
        }

        @Override
        public PatchEvidenceMetadataRequest deserialize(JsonParser parser, DeserializationContext context) {
            Class<PatchEvidenceMetadataRequest> type = PatchEvidenceMetadataRequest.class;
            JsonNode root = StrictEvidenceJson.object(parser, context, type, FIELDS);
            EnumSet<Field> present = EnumSet.noneOf(Field.class);
            if (root.has("title")) present.add(Field.TITLE);
            if (root.has("description")) present.add(Field.DESCRIPTION);
            if (root.has("sourceType")) present.add(Field.SOURCE_TYPE);
            if (root.has("sourceDescription")) present.add(Field.SOURCE_DESCRIPTION);
            if (root.has("sourceManufacturer")) present.add(Field.SOURCE_MANUFACTURER);
            if (root.has("sourceModel")) present.add(Field.SOURCE_MODEL);
            if (root.has("sourceSerialNumber")) present.add(Field.SOURCE_SERIAL_NUMBER);
            if (root.has("sourceLogicalIdentifier")) present.add(Field.SOURCE_LOGICAL_IDENTIFIER);
            if (root.has("acquisitionMethod")) present.add(Field.ACQUISITION_METHOD);
            if (root.has("acquiredAt")) present.add(Field.ACQUIRED_AT);
            if (root.has("acquisitionLocation")) present.add(Field.ACQUISITION_LOCATION);
            if (root.has("acquisitionToolName")) present.add(Field.ACQUISITION_TOOL_NAME);
            if (root.has("acquisitionToolVersion")) present.add(Field.ACQUISITION_TOOL_VERSION);
            if (root.has("acquisitionNotes")) present.add(Field.ACQUISITION_NOTES);
            return new PatchEvidenceMetadataRequest(
                    StrictEvidenceJson.nullableString(root, "title", context, type),
                    StrictEvidenceJson.nullableString(root, "description", context, type),
                    StrictEvidenceJson.nullableEnum(root, "sourceType", SourceType.class, context, type),
                    StrictEvidenceJson.nullableString(root, "sourceDescription", context, type),
                    StrictEvidenceJson.nullableString(root, "sourceManufacturer", context, type),
                    StrictEvidenceJson.nullableString(root, "sourceModel", context, type),
                    StrictEvidenceJson.nullableString(root, "sourceSerialNumber", context, type),
                    StrictEvidenceJson.nullableString(root, "sourceLogicalIdentifier", context, type),
                    StrictEvidenceJson.nullableEnum(root, "acquisitionMethod", AcquisitionMethod.class, context, type),
                    StrictEvidenceJson.nullableInstant(root, "acquiredAt", context, type),
                    StrictEvidenceJson.nullableString(root, "acquisitionLocation", context, type),
                    StrictEvidenceJson.nullableString(root, "acquisitionToolName", context, type),
                    StrictEvidenceJson.nullableString(root, "acquisitionToolVersion", context, type),
                    StrictEvidenceJson.nullableString(root, "acquisitionNotes", context, type),
                    StrictEvidenceJson.nullableString(root, "reason", context, type),
                    present);
        }
    }
}
