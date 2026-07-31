package it.itsprodigi.proofchain.evidence.maintenance;

import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/**
 * One sanitized observation about the evidence storage tree.
 *
 * <p>A finding carries exactly three kinds of value: a classification, a closed-vocabulary reason and a storage-root
 * relative path. It can never carry an absolute host path, the original upload file name, a media type, a hash, a
 * byte count or any other evidence metadata, because the constructor rejects everything else. The optional evidence
 * identifier is populated only when the path itself had to be withheld, so that a Project Owner-controlled
 * investigation still has a database key to start from.
 */
public record OrphanFileFinding(
        OrphanFileClassification classification, OrphanFileReason reason, String path, UUID evidenceId)
        implements Comparable<OrphanFileFinding> {

    /**
     * The placeholder used when an evidence row carries a storage key that is not a canonical relative key. Such a
     * value is attacker-influenced and may contain an absolute path, so it is never echoed into a report.
     */
    public static final String WITHHELD_PATH = "(withheld)";

    private static final Comparator<OrphanFileFinding> ORDER = Comparator.comparing(OrphanFileFinding::path)
            .thenComparing(OrphanFileFinding::classification)
            .thenComparing(OrphanFileFinding::reason)
            .thenComparing(finding ->
                    finding.evidenceId() == null ? "" : finding.evidenceId().toString());

    public OrphanFileFinding {
        Objects.requireNonNull(classification, "classification must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        requireSafePath(path);
    }

    /** A finding about an entry that exists under the storage root, identified only by its safe relative path. */
    public static OrphanFileFinding at(OrphanFileClassification classification, OrphanFileReason reason, String path) {
        return new OrphanFileFinding(classification, reason, path, null);
    }

    /** A finding about an evidence row whose storage key cannot be published. */
    public static OrphanFileFinding withheld(
            OrphanFileClassification classification, OrphanFileReason reason, UUID evidenceId) {
        return new OrphanFileFinding(
                classification,
                reason,
                WITHHELD_PATH,
                Objects.requireNonNull(evidenceId, "evidenceId must not be null"));
    }

    @Override
    public int compareTo(OrphanFileFinding other) {
        return ORDER.compare(this, other);
    }

    private static void requireSafePath(String path) {
        Objects.requireNonNull(path, "path must not be null");
        if (path.equals(WITHHELD_PATH)) {
            return;
        }
        if (path.isEmpty()
                || path.startsWith("/")
                || path.startsWith("./")
                || path.indexOf('\\') >= 0
                || path.indexOf(':') >= 0
                || path.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("A finding path must be a safe relative storage path");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("A finding path must be a safe relative storage path");
            }
        }
    }
}
