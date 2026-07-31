package it.itsprodigi.proofchain.custodyevent.protocol;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class CustodyEventCanonicalizer {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final DateTimeFormatter INSTANT_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 6, 6, true)
            .appendLiteral('Z')
            .toFormatter(Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private CustodyEventCanonicalizer() {}

    public static String canonicalize(CanonicalCustodyEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        StringBuilder json = new StringBuilder(1024);
        json.append('{');
        stringField(json, "actorRole", event.actorRole().name(), true);
        uuidField(json, "caseId", event.caseId(), false);
        uuidField(json, "eventId", event.eventId(), false);
        stringField(json, "eventType", event.eventType().name(), false);
        uuidField(json, "evidenceId", event.evidenceId(), false);
        instantField(json, "occurredAt", event.occurredAt(), false);
        uuidField(json, "operatorId", event.operatorId(), false);
        name(json, "payload", false);
        appendPayload(json, event.payload());
        numberField(json, "payloadVersion", event.payloadVersion(), false);
        stringField(json, "previousHash", event.previousHash(), false);
        numberField(json, "sequenceNumber", event.sequenceNumber(), false);
        return json.append('}').toString();
    }

    public static byte[] canonicalBytes(CanonicalCustodyEvent event) {
        return canonicalize(event).getBytes(StandardCharsets.UTF_8);
    }

    public static String canonicalizePayload(CustodyEventPayload payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        StringBuilder json = new StringBuilder(768);
        appendPayload(json, payload);
        return json.toString();
    }

    private static void appendPayload(StringBuilder json, CustodyEventPayload payload) {
        switch (payload) {
            case EvidenceRegisteredPayload registered -> appendRegistered(json, registered);
            case CustodyTransferredPayload transferred -> appendTransferred(json, transferred);
            case MetadataUpdatedPayload updated -> appendMetadataUpdated(json, updated);
            case IntegrityVerifiedPayload verified -> appendIntegrityVerified(json, verified);
            case EvidenceSealedPayload sealed -> appendSealed(json, sealed);
            case EvidenceReleasedPayload released -> appendReleased(json, released);
        }
    }

    private static void appendRegistered(StringBuilder json, EvidenceRegisteredPayload payload) {
        json.append('{');
        nullableInstantField(json, "acquiredAt", payload.acquiredAt(), true);
        nullableStringField(json, "acquisitionLocation", payload.acquisitionLocation(), false);
        stringField(json, "acquisitionMethod", payload.acquisitionMethod().name(), false);
        nullableStringField(json, "acquisitionNotes", payload.acquisitionNotes(), false);
        nullableStringField(json, "acquisitionToolName", payload.acquisitionToolName(), false);
        nullableStringField(json, "acquisitionToolVersion", payload.acquisitionToolVersion(), false);
        booleanField(json, "backfilled", payload.backfilled(), false);
        stringField(json, "contentSha256", payload.contentSha256(), false);
        stringField(json, "contextualSha256", payload.contextualSha256(), false);
        nullableStringField(json, "description", payload.description(), false);
        nullableStringField(json, "fileExtension", payload.fileExtension(), false);
        numberField(json, "fileSize", payload.fileSize(), false);
        uuidField(json, "initialHolderId", payload.initialHolderId(), false);
        stringField(json, "mediaType", payload.mediaType(), false);
        stringField(json, "originalFilename", payload.originalFilename(), false);
        nullableStringField(json, "referenceTag", payload.referenceTag(), false);
        nullableStringField(json, "sourceDescription", payload.sourceDescription(), false);
        nullableStringField(json, "sourceLogicalIdentifier", payload.sourceLogicalIdentifier(), false);
        nullableStringField(json, "sourceManufacturer", payload.sourceManufacturer(), false);
        nullableStringField(json, "sourceModel", payload.sourceModel(), false);
        nullableStringField(json, "sourceSerialNumber", payload.sourceSerialNumber(), false);
        stringField(json, "sourceType", payload.sourceType().name(), false);
        stringField(json, "status", payload.status().name(), false);
        stringField(json, "title", payload.title(), false);
        uuidField(json, "uploadedById", payload.uploadedById(), false);
        json.append('}');
    }

    private static void appendTransferred(StringBuilder json, CustodyTransferredPayload payload) {
        json.append('{');
        uuidField(json, "newHolderId", payload.newHolderId(), true);
        uuidField(json, "previousHolderId", payload.previousHolderId(), false);
        stringField(json, "reason", payload.reason(), false);
        json.append('}');
    }

    private static void appendMetadataUpdated(StringBuilder json, MetadataUpdatedPayload payload) {
        json.append('{');
        name(json, "after", true);
        appendMetadataSnapshot(json, payload.after());
        name(json, "before", false);
        appendMetadataSnapshot(json, payload.before());
        stringField(json, "reason", payload.reason(), false);
        json.append('}');
    }

    private static void appendMetadataSnapshot(StringBuilder json, EvidenceMetadataSnapshot snapshot) {
        json.append('{');
        nullableInstantField(json, "acquiredAt", snapshot.acquiredAt(), true);
        nullableStringField(json, "acquisitionLocation", snapshot.acquisitionLocation(), false);
        stringField(json, "acquisitionMethod", snapshot.acquisitionMethod().name(), false);
        nullableStringField(json, "acquisitionNotes", snapshot.acquisitionNotes(), false);
        nullableStringField(json, "acquisitionToolName", snapshot.acquisitionToolName(), false);
        nullableStringField(json, "acquisitionToolVersion", snapshot.acquisitionToolVersion(), false);
        nullableStringField(json, "description", snapshot.description(), false);
        nullableStringField(json, "sourceDescription", snapshot.sourceDescription(), false);
        nullableStringField(json, "sourceLogicalIdentifier", snapshot.sourceLogicalIdentifier(), false);
        nullableStringField(json, "sourceManufacturer", snapshot.sourceManufacturer(), false);
        nullableStringField(json, "sourceModel", snapshot.sourceModel(), false);
        nullableStringField(json, "sourceSerialNumber", snapshot.sourceSerialNumber(), false);
        stringField(json, "sourceType", snapshot.sourceType().name(), false);
        stringField(json, "title", snapshot.title(), false);
        json.append('}');
    }

    private static void appendIntegrityVerified(StringBuilder json, IntegrityVerifiedPayload payload) {
        json.append('{');
        stringField(json, "actualContentSha256", payload.actualContentSha256(), true);
        stringField(json, "algorithm", payload.algorithm(), false);
        stringField(json, "expectedContentSha256", payload.expectedContentSha256(), false);
        numberField(json, "fileSize", payload.fileSize(), false);
        booleanField(json, "valid", payload.valid(), false);
        json.append('}');
    }

    private static void appendSealed(StringBuilder json, EvidenceSealedPayload payload) {
        json.append('{');
        uuidField(json, "holderId", payload.holderId(), true);
        stringField(json, "newStatus", payload.newStatus().name(), false);
        stringField(json, "previousStatus", payload.previousStatus().name(), false);
        stringField(json, "reason", payload.reason(), false);
        json.append('}');
    }

    private static void appendReleased(StringBuilder json, EvidenceReleasedPayload payload) {
        json.append('{');
        nullField(json, "newHolderId", true);
        stringField(json, "newStatus", payload.newStatus().name(), false);
        uuidField(json, "previousHolderId", payload.previousHolderId(), false);
        stringField(json, "previousStatus", payload.previousStatus().name(), false);
        stringField(json, "reason", payload.reason(), false);
        json.append('}');
    }

    private static void stringField(StringBuilder json, String fieldName, String value, boolean first) {
        name(json, fieldName, first);
        appendString(json, value);
    }

    private static void nullableStringField(StringBuilder json, String fieldName, String value, boolean first) {
        if (value == null) {
            nullField(json, fieldName, first);
        } else {
            stringField(json, fieldName, value, first);
        }
    }

    private static void uuidField(StringBuilder json, String fieldName, UUID value, boolean first) {
        stringField(json, fieldName, value.toString(), first);
    }

    private static void instantField(StringBuilder json, String fieldName, Instant value, boolean first) {
        stringField(json, fieldName, INSTANT_FORMATTER.format(value), first);
    }

    private static void nullableInstantField(StringBuilder json, String fieldName, Instant value, boolean first) {
        if (value == null) {
            nullField(json, fieldName, first);
        } else {
            instantField(json, fieldName, value, first);
        }
    }

    private static void numberField(StringBuilder json, String fieldName, long value, boolean first) {
        name(json, fieldName, first);
        json.append(value);
    }

    private static void booleanField(StringBuilder json, String fieldName, boolean value, boolean first) {
        name(json, fieldName, first);
        json.append(value);
    }

    private static void nullField(StringBuilder json, String fieldName, boolean first) {
        name(json, fieldName, first);
        json.append("null");
    }

    private static void name(StringBuilder json, String fieldName, boolean first) {
        if (!first) {
            json.append(',');
        }
        appendString(json, fieldName);
        json.append(':');
    }

    private static void appendString(StringBuilder json, String value) {
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character <= 0x1f) {
                        appendUnicodeEscape(json, character);
                    } else if (Character.isHighSurrogate(character)) {
                        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                            throw new IllegalArgumentException("JSON string contains an unpaired surrogate");
                        }
                        json.append(character).append(value.charAt(++index));
                    } else if (Character.isLowSurrogate(character)) {
                        throw new IllegalArgumentException("JSON string contains an unpaired surrogate");
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        json.append('"');
    }

    private static void appendUnicodeEscape(StringBuilder json, char character) {
        json.append("\\u")
                .append(HEX[(character >>> 12) & 0x0f])
                .append(HEX[(character >>> 8) & 0x0f])
                .append(HEX[(character >>> 4) & 0x0f])
                .append(HEX[character & 0x0f]);
    }
}
