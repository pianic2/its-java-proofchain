package it.itsprodigi.proofchain.custodycase.api;

import java.util.Set;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;

final class StrictCaseJson {

    private StrictCaseJson() {}

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
}
