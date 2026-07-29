package it.itsprodigi.proofchain.evidence.api;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;

final class StrictEvidenceJson {

    private StrictEvidenceJson() {}

    static JsonNode object(
            JsonParser parser, DeserializationContext context, Class<?> requestType, Set<String> fields) {
        JsonNode root = context.readTree(parser);
        if (root == null || !root.isObject()) {
            return context.reportInputMismatch(requestType, "request body must be a JSON object");
        }
        for (String property : root.propertyNames()) {
            if (!fields.contains(property)) {
                return context.reportPropertyInputMismatch(
                        requestType, property, "unknown request property '%s'", property);
            }
        }
        return root;
    }

    static String nullableString(JsonNode root, String field, DeserializationContext context, Class<?> requestType) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            return context.reportPropertyInputMismatch(requestType, field, "'%s' must be a string or null", field);
        }
        return value.stringValue();
    }

    static <E extends Enum<E>> E nullableEnum(
            JsonNode root, String field, Class<E> enumType, DeserializationContext context, Class<?> requestType) {
        String value = nullableString(root, field, context, requestType);
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException exception) {
            return context.reportPropertyInputMismatch(
                    requestType, field, "'%s' is not a supported value for '%s'", value, field);
        }
    }

    static Instant nullableInstant(JsonNode root, String field, DeserializationContext context, Class<?> requestType) {
        String value = nullableString(root, field, context, requestType);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            return context.reportPropertyInputMismatch(requestType, field, "'%s' must be an ISO-8601 instant", field);
        }
    }

    static UUID nullableUuid(JsonNode root, String field, DeserializationContext context, Class<?> requestType) {
        String value = nullableString(root, field, context, requestType);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return context.reportPropertyInputMismatch(requestType, field, "'%s' must be a UUID", field);
        }
    }
}
