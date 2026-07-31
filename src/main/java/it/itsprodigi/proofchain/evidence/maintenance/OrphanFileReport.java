package it.itsprodigi.proofchain.evidence.maintenance;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * The deterministic result of one offline storage scan.
 *
 * <p>Determinism is a property of the value, not of the caller: findings are deduplicated and totally ordered here, and
 * the rendered document contains no timestamp, no host name, no configuration value and no random identifier. Two scans
 * of the same database and the same storage tree therefore render byte-identical documents, which is what makes a
 * report comparable across an investigation.
 */
public record OrphanFileReport(
        long examinedEvidenceRows, long examinedStorageEntries, List<OrphanFileFinding> findings) {

    /** Rendered into every document so a stored report stays interpretable after the format evolves. */
    public static final int FORMAT_VERSION = 1;

    private static final String DOCUMENT_NAME = "evidence-orphan-file-report";

    public OrphanFileReport {
        if (examinedEvidenceRows < 0 || examinedStorageEntries < 0) {
            throw new IllegalArgumentException("Examined counters must not be negative");
        }
        findings = List.copyOf(new TreeSet<>(Objects.requireNonNull(findings, "findings must not be null")));
    }

    /** Finding totals per classification, always listing every classification, in declaration order. */
    public Map<OrphanFileClassification, Long> countsByClassification() {
        Map<OrphanFileClassification, Long> counts = new EnumMap<>(OrphanFileClassification.class);
        for (OrphanFileClassification classification : OrphanFileClassification.values()) {
            counts.put(classification, 0L);
        }
        for (OrphanFileFinding finding : findings) {
            counts.merge(finding.classification(), 1L, Long::sum);
        }
        return counts;
    }

    /** The report as a deterministic JSON document. */
    public String toJson() {
        StringBuilder json = new StringBuilder(256);
        json.append("{\n");
        json.append("  \"report\": \"").append(DOCUMENT_NAME).append("\",\n");
        json.append("  \"formatVersion\": ").append(FORMAT_VERSION).append(",\n");
        json.append("  \"summary\": {\n");
        json.append("    \"examinedEvidenceRows\": ")
                .append(examinedEvidenceRows)
                .append(",\n");
        json.append("    \"examinedStorageEntries\": ")
                .append(examinedStorageEntries)
                .append(",\n");
        json.append("    \"findings\": ").append(findings.size());
        for (Map.Entry<OrphanFileClassification, Long> count :
                countsByClassification().entrySet()) {
            json.append(",\n    \"")
                    .append(count.getKey().name())
                    .append("\": ")
                    .append(count.getValue());
        }
        json.append("\n  },\n");
        json.append("  \"findings\": [");
        for (int index = 0; index < findings.size(); index++) {
            OrphanFileFinding finding = findings.get(index);
            json.append(index == 0 ? "\n" : ",\n");
            json.append("    {\"classification\": \"")
                    .append(finding.classification().name())
                    .append("\", \"reason\": \"")
                    .append(finding.reason().name())
                    .append("\", \"path\": \"")
                    .append(escape(finding.path()))
                    .append('"');
            if (finding.evidenceId() != null) {
                json.append(", \"evidenceId\": \"").append(finding.evidenceId()).append('"');
            }
            json.append('}');
        }
        json.append(findings.isEmpty() ? "]\n" : "\n  ]\n");
        json.append("}\n");
        return json.toString();
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                default -> {
                    if (character < 0x20 || character == 0x7f) {
                        escaped.append("\\u%04x".formatted((int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
