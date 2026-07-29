package it.itsprodigi.proofchain.custodycase.api;

import io.swagger.v3.oas.annotations.media.Schema;
import it.itsprodigi.proofchain.custodycase.domain.CaseStatus;
import jakarta.validation.constraints.NotNull;
import java.util.Set;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.std.StdDeserializer;

@JsonDeserialize(using = UpdateCaseStatusRequest.Deserializer.class)
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record UpdateCaseStatusRequest(
        @NotNull
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                nullable = false,
                description = "CLOSED performs or repeats closure; OPEN returns a 409 transition conflict.")
        CaseStatus status) {

    static final class Deserializer extends StdDeserializer<UpdateCaseStatusRequest> {

        private static final Set<String> FIELDS = Set.of("status");

        Deserializer() {
            super(UpdateCaseStatusRequest.class);
        }

        @Override
        public UpdateCaseStatusRequest deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode root = StrictCaseJson.object(parser, context, UpdateCaseStatusRequest.class, FIELDS);
            return new UpdateCaseStatusRequest(StrictCaseJson.nullableEnum(
                    root, "status", CaseStatus.class, context, UpdateCaseStatusRequest.class));
        }
    }
}
