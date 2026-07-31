package it.itsprodigi.proofchain.evidence.maintenance;

import it.itsprodigi.proofchain.evidence.application.EvidenceStorageKeyFactory;
import it.itsprodigi.proofchain.evidence.application.UnsafeEvidenceStoragePathException;
import it.itsprodigi.proofchain.evidence.storage.EvidenceStorageProperties;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Read-only reconciliation between the evidence rows and the canonical storage tree.
 *
 * <p>This service is a diagnostic and nothing else. It opens no file for writing, creates no directory, moves nothing,
 * quarantines nothing and deletes nothing — in particular it never deletes a final content file merely because no
 * database row references it, because the missing row may be the thing that is wrong. Every filesystem call it makes is
 * either a directory listing or an attribute read, and every attribute read refuses to follow a symbolic link.
 *
 * <p>The scan is never triggered by application startup, by a health probe or by an HTTP request. It runs only from the
 * offline maintenance command, so a storage tree with millions of entries can never turn a deployment into a
 * long-running boot.
 */
public final class OrphanFileReportService {

    private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};
    private static final String STAGING_DIRECTORY = ".staging";
    private static final String CASES_DIRECTORY = "cases";
    private static final String EVIDENCES_DIRECTORY = "evidences";
    private static final String CONTENT_FILE = "content.bin";
    private static final String RESERVATION_SUFFIX = ".lock";

    private final Path configuredRoot;
    private final EvidenceStorageKeyCatalog catalog;

    public OrphanFileReportService(EvidenceStorageProperties properties, EvidenceStorageKeyCatalog catalog) {
        Objects.requireNonNull(properties, "properties must not be null");
        this.configuredRoot = properties.root().toAbsolutePath().normalize();
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
    }

    /** Produces one deterministic report. Calling it twice on unchanged inputs produces two identical reports. */
    public OrphanFileReport scan() {
        Path root = requireTrustedRoot();
        List<EvidenceStorageKeyEntry> rows = catalog.storageKeys();
        Set<String> canonicalKeys = canonicalKeys(rows);
        Set<OrphanFileFinding> findings = new TreeSet<>();
        long examined = scanRoot(root, findings, canonicalKeys);
        inspectRows(root, rows, findings);
        return new OrphanFileReport(rows.size(), examined, List.copyOf(findings));
    }

    private Path requireTrustedRoot() {
        try {
            if (Files.isSymbolicLink(configuredRoot) || !Files.isDirectory(configuredRoot, NOFOLLOW)) {
                throw new OrphanFileReportException("The configured evidence storage root is not a usable directory");
            }
            if (!configuredRoot.toRealPath().equals(configuredRoot)) {
                throw new OrphanFileReportException(
                        "The configured evidence storage root resolves through a symbolic link");
            }
            return configuredRoot;
        } catch (IOException exception) {
            throw new OrphanFileReportException("The configured evidence storage root cannot be resolved");
        }
    }

    private static Set<String> canonicalKeys(List<EvidenceStorageKeyEntry> rows) {
        Set<String> keys = new HashSet<>();
        for (EvidenceStorageKeyEntry row : rows) {
            try {
                keys.add(EvidenceStorageKeyFactory.requireCanonical(row.storageKey()));
            } catch (UnsafeEvidenceStoragePathException exception) {
                // A non-canonical key is reported separately and can never match a canonical file.
            }
        }
        return keys;
    }

    private long scanRoot(Path root, Set<OrphanFileFinding> findings, Set<String> canonicalKeys) {
        long examined = 0;
        List<Path> children = children(root, "", findings);
        for (Path child : children) {
            examined++;
            String name = name(child);
            if (flagUnsafeEntry(child, name, findings)) {
                continue;
            }
            if (STAGING_DIRECTORY.equals(name) && isDirectory(child)) {
                examined += scanStaging(child, findings);
            } else if (CASES_DIRECTORY.equals(name) && isDirectory(child)) {
                examined += scanCases(child, findings, canonicalKeys);
            } else {
                findings.add(unexpected(OrphanFileReason.NOT_IN_CANONICAL_LAYOUT, name));
            }
        }
        return examined;
    }

    private long scanStaging(Path staging, Set<OrphanFileFinding> findings) {
        long examined = 0;
        for (Path child : children(staging, STAGING_DIRECTORY, findings)) {
            examined++;
            String relative = STAGING_DIRECTORY + "/" + name(child);
            if (!flagUnsafeEntry(child, relative, findings)) {
                findings.add(unexpected(OrphanFileReason.STAGING_RESIDUE, relative));
            }
        }
        return examined;
    }

    private long scanCases(Path cases, Set<OrphanFileFinding> findings, Set<String> canonicalKeys) {
        long examined = 0;
        for (Path child : children(cases, CASES_DIRECTORY, findings)) {
            examined++;
            String relative = CASES_DIRECTORY + "/" + name(child);
            if (flagUnsafeEntry(child, relative, findings)) {
                continue;
            }
            if (isCanonicalUuid(name(child)) && isDirectory(child)) {
                examined += scanCase(child, relative, findings, canonicalKeys);
            } else {
                findings.add(unexpected(OrphanFileReason.NOT_IN_CANONICAL_LAYOUT, relative));
            }
        }
        return examined;
    }

    private long scanCase(Path caseDirectory, String prefix, Set<OrphanFileFinding> findings, Set<String> keys) {
        long examined = 0;
        for (Path child : children(caseDirectory, prefix, findings)) {
            examined++;
            String relative = prefix + "/" + name(child);
            if (flagUnsafeEntry(child, relative, findings)) {
                continue;
            }
            if (EVIDENCES_DIRECTORY.equals(name(child)) && isDirectory(child)) {
                examined += scanEvidences(child, relative, findings, keys);
            } else {
                findings.add(unexpected(OrphanFileReason.NOT_IN_CANONICAL_LAYOUT, relative));
            }
        }
        return examined;
    }

    private long scanEvidences(Path evidences, String prefix, Set<OrphanFileFinding> findings, Set<String> keys) {
        long examined = 0;
        for (Path child : children(evidences, prefix, findings)) {
            examined++;
            String relative = prefix + "/" + name(child);
            if (flagUnsafeEntry(child, relative, findings)) {
                continue;
            }
            if (isCanonicalUuid(name(child)) && isDirectory(child)) {
                examined += scanEvidence(child, relative, findings, keys);
            } else {
                findings.add(unexpected(OrphanFileReason.NOT_IN_CANONICAL_LAYOUT, relative));
            }
        }
        return examined;
    }

    private long scanEvidence(Path evidence, String prefix, Set<OrphanFileFinding> findings, Set<String> keys) {
        long examined = 0;
        for (Path child : children(evidence, prefix, findings)) {
            examined++;
            String name = name(child);
            String relative = prefix + "/" + name;
            if (flagUnsafeEntry(child, relative, findings)) {
                continue;
            }
            if (CONTENT_FILE.equals(name)) {
                classifyContent(child, relative, findings, keys);
            } else if (name.endsWith(RESERVATION_SUFFIX)) {
                findings.add(unexpected(OrphanFileReason.RESERVATION_RESIDUE, relative));
            } else {
                findings.add(unexpected(OrphanFileReason.NOT_IN_CANONICAL_LAYOUT, relative));
            }
        }
        return examined;
    }

    private void classifyContent(Path content, String relative, Set<OrphanFileFinding> findings, Set<String> keys) {
        // Symbolic links and non-regular entries were already classified before this method is reached, so the only
        // remaining possibilities are a directory standing in for the content file and a real regular file.
        if (isDirectory(content)) {
            findings.add(unsafe(OrphanFileReason.DIRECTORY_INSTEAD_OF_CONTENT, relative));
            return;
        }
        if (!keys.contains(relative)) {
            findings.add(OrphanFileFinding.at(
                    OrphanFileClassification.ORPHAN_CONTENT, OrphanFileReason.NO_EVIDENCE_ROW, relative));
        }
    }

    private void inspectRows(Path root, List<EvidenceStorageKeyEntry> rows, Set<OrphanFileFinding> findings) {
        for (EvidenceStorageKeyEntry row : rows) {
            inspectRow(root, row.evidenceId(), row.storageKey(), findings);
        }
    }

    private void inspectRow(Path root, UUID evidenceId, String storageKey, Set<OrphanFileFinding> findings) {
        String canonicalKey;
        try {
            canonicalKey = EvidenceStorageKeyFactory.requireCanonical(storageKey);
        } catch (UnsafeEvidenceStoragePathException exception) {
            findings.add(OrphanFileFinding.withheld(
                    OrphanFileClassification.UNSAFE_CONTENT, OrphanFileReason.STORAGE_KEY_NOT_CANONICAL, evidenceId));
            return;
        }
        Path target = root.resolve(canonicalKey).normalize();
        if (!target.startsWith(root)) {
            findings.add(OrphanFileFinding.withheld(
                    OrphanFileClassification.UNSAFE_CONTENT, OrphanFileReason.STORAGE_KEY_OUTSIDE_ROOT, evidenceId));
            return;
        }
        String linkedAncestor = firstSymbolicLinkAncestor(root, canonicalKey);
        if (linkedAncestor != null) {
            findings.add(unsafe(OrphanFileReason.SYMBOLIC_LINK, linkedAncestor));
            return;
        }
        if (Files.isSymbolicLink(target)) {
            findings.add(unsafe(OrphanFileReason.SYMBOLIC_LINK, canonicalKey));
            return;
        }
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(target, BasicFileAttributes.class, NOFOLLOW);
        } catch (NoSuchFileException exception) {
            findings.add(missing(OrphanFileReason.CONTENT_ABSENT, canonicalKey));
            return;
        } catch (IOException exception) {
            findings.add(missing(OrphanFileReason.CONTENT_UNREADABLE, canonicalKey));
            return;
        }
        if (attributes.isDirectory()) {
            findings.add(unsafe(OrphanFileReason.DIRECTORY_INSTEAD_OF_CONTENT, canonicalKey));
        } else if (!attributes.isRegularFile()) {
            findings.add(unsafe(OrphanFileReason.NON_REGULAR_FILE, canonicalKey));
        } else if (!Files.isReadable(target)) {
            findings.add(missing(OrphanFileReason.CONTENT_UNREADABLE, canonicalKey));
        }
    }

    private static String firstSymbolicLinkAncestor(Path root, String canonicalKey) {
        String[] segments = canonicalKey.split("/", -1);
        Path current = root;
        StringBuilder relative = new StringBuilder();
        for (int index = 0; index < segments.length - 1; index++) {
            current = current.resolve(segments[index]);
            relative.append(index == 0 ? "" : "/").append(segments[index]);
            if (Files.isSymbolicLink(current)) {
                return relative.toString();
            }
        }
        return null;
    }

    private boolean flagUnsafeEntry(Path entry, String relative, Set<OrphanFileFinding> findings) {
        if (Files.isSymbolicLink(entry)) {
            findings.add(unsafe(OrphanFileReason.SYMBOLIC_LINK, relative));
            return true;
        }
        if (!isDirectory(entry) && !isRegularFile(entry)) {
            findings.add(unsafe(OrphanFileReason.NON_REGULAR_FILE, relative));
            return true;
        }
        return false;
    }

    private List<Path> children(Path directory, String relative, Set<OrphanFileFinding> findings) {
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            stream.forEach(children::add);
        } catch (IOException | RuntimeException exception) {
            if (!relative.isEmpty()) {
                findings.add(unsafe(OrphanFileReason.DIRECTORY_UNREADABLE, relative));
                return List.of();
            }
            throw new OrphanFileReportException("The evidence storage root could not be listed");
        }
        children.sort(Comparator.comparing(OrphanFileReportService::name));
        return children;
    }

    private static String name(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString();
    }

    private static boolean isDirectory(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean isRegularFile(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean isCanonicalUuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static OrphanFileFinding unexpected(OrphanFileReason reason, String relative) {
        return OrphanFileFinding.at(OrphanFileClassification.UNEXPECTED_ENTRY, reason, relative);
    }

    private static OrphanFileFinding unsafe(OrphanFileReason reason, String relative) {
        return OrphanFileFinding.at(OrphanFileClassification.UNSAFE_CONTENT, reason, relative);
    }

    private static OrphanFileFinding missing(OrphanFileReason reason, String relative) {
        return OrphanFileFinding.at(OrphanFileClassification.MISSING_CONTENT, reason, relative);
    }
}
