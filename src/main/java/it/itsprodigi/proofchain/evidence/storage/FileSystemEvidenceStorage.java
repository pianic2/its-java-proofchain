package it.itsprodigi.proofchain.evidence.storage;

import it.itsprodigi.proofchain.evidence.application.EmptyEvidenceException;
import it.itsprodigi.proofchain.evidence.application.EvidenceFileUnavailableException;
import it.itsprodigi.proofchain.evidence.application.EvidenceStorageFailureException;
import it.itsprodigi.proofchain.evidence.application.EvidenceStorageKeyFactory;
import it.itsprodigi.proofchain.evidence.application.EvidenceStoragePort;
import it.itsprodigi.proofchain.evidence.application.EvidenceTargetExistsException;
import it.itsprodigi.proofchain.evidence.application.EvidenceTooLargeException;
import it.itsprodigi.proofchain.evidence.application.OpenedEvidence;
import it.itsprodigi.proofchain.evidence.application.StagedEvidence;
import it.itsprodigi.proofchain.evidence.application.UnsafeEvidenceStoragePathException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

public final class FileSystemEvidenceStorage implements EvidenceStoragePort {

    private static final int BUFFER_SIZE = 8192;
    private static final int MAXIMUM_STALLED_READS = 64;
    private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};
    private static final Set<OpenOption> READ_NOFOLLOW = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);

    private final Object owner = new Object();
    private final Path trustedRoot;
    private final Path stagingDirectory;
    private final long maximumBytes;

    public FileSystemEvidenceStorage(EvidenceStorageProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        maximumBytes = properties.maxFileSize().toBytes();
        trustedRoot = initializeRoot(properties.root());
        stagingDirectory = trustedRoot.resolve(".staging");
        ensureDirectory(stagingDirectory);
    }

    @Override
    public StagedEvidence stage(String storageKey, InputStream content) {
        String canonicalKey = EvidenceStorageKeyFactory.requireCanonical(storageKey);
        Objects.requireNonNull(content, "content must not be null");
        ensureTrustedDirectory(stagingDirectory);
        Path temporary = createTemporaryFile();

        try (OutputStream output = Files.newOutputStream(
                temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
            MessageDigest digest = newSha256();
            byte[] buffer = new byte[BUFFER_SIZE];
            long byteCount = 0;
            int stalledReads = 0;
            while (true) {
                long remaining = maximumBytes - byteCount;
                int requested = remaining >= BUFFER_SIZE ? BUFFER_SIZE : Math.toIntExact(remaining + 1);
                int read = content.read(buffer, 0, requested);
                if (read == -1) {
                    break;
                }
                if (read == 0) {
                    // A source that never returns a byte and never signals the end of the stream violates the
                    // InputStream contract. Tolerating it forever would let one upload occupy a request thread and a
                    // staged temporary file indefinitely, so progress is required within a bounded number of reads.
                    if (++stalledReads > MAXIMUM_STALLED_READS) {
                        throw new EvidenceStorageFailureException("Unable to stage evidence content");
                    }
                    continue;
                }
                stalledReads = 0;
                if (read > remaining) {
                    throw new EvidenceTooLargeException(maximumBytes);
                }
                byteCount = Math.addExact(byteCount, read);
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
            }
            if (byteCount == 0) {
                throw new EmptyEvidenceException();
            }
            return new FileSystemStagedEvidence(
                    owner, canonicalKey, byteCount, HexFormat.of().formatHex(digest.digest()), temporary);
        } catch (EmptyEvidenceException | EvidenceTooLargeException exception) {
            cleanupStagedAfterFailure(temporary);
            throw exception;
        } catch (IOException | ArithmeticException exception) {
            cleanupStagedAfterFailure(temporary);
            throw new EvidenceStorageFailureException("Unable to stage evidence content");
        } catch (RuntimeException exception) {
            cleanupStagedAfterFailure(temporary);
            throw new EvidenceStorageFailureException("Unable to stage evidence content");
        }
    }

    @Override
    public void finalizeStaged(StagedEvidence stagedEvidence) {
        FileSystemStagedEvidence staged = requireOwned(stagedEvidence);
        String canonicalKey = EvidenceStorageKeyFactory.requireCanonical(staged.storageKey());
        Path target = resolveCanonical(canonicalKey);
        Path lock = target.resolveSibling(target.getFileName() + ".lock");
        boolean reservationAcquired = false;

        try {
            requireSafeStagedFile(staged.temporary());
            ensureSafeParentDirectories(target.getParent());
            rejectSymlinksInExistingPath(target);
            if (Files.exists(target, NOFOLLOW)) {
                throw new EvidenceTargetExistsException();
            }
            reserve(lock);
            reservationAcquired = true;
            try {
                rejectSymlinksInExistingPath(target);
                if (Files.exists(target, NOFOLLOW)) {
                    throw new EvidenceTargetExistsException();
                }
                Files.move(staged.temporary(), target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new EvidenceStorageFailureException("Atomic evidence finalization is unavailable");
            } catch (FileAlreadyExistsException exception) {
                throw new EvidenceTargetExistsException();
            } catch (IOException exception) {
                throw new EvidenceStorageFailureException("Unable to finalize evidence content");
            }
        } catch (EvidenceTargetExistsException | UnsafeEvidenceStoragePathException exception) {
            cleanupFinalizeFailure(staged.temporary());
            throw exception;
        } catch (EvidenceStorageFailureException exception) {
            cleanupFinalizeFailure(staged.temporary());
            throw exception;
        } finally {
            if (reservationAcquired) {
                deleteReservation(lock);
            }
        }
    }

    @Override
    public void discardStaged(StagedEvidence stagedEvidence) {
        FileSystemStagedEvidence staged = requireOwned(stagedEvidence);
        try {
            Files.deleteIfExists(staged.temporary());
        } catch (IOException exception) {
            throw new EvidenceStorageFailureException("Unable to discard staged evidence content");
        }
    }

    @Override
    public void discardFinalized(String storageKey) {
        Path target = resolveCanonical(EvidenceStorageKeyFactory.requireCanonical(storageKey));
        rejectSymlinksInExistingPath(target);
        if (!Files.exists(target, NOFOLLOW)) {
            return;
        }
        if (!Files.isRegularFile(target, NOFOLLOW)) {
            throw new EvidenceStorageFailureException("Unable to discard finalized evidence content");
        }
        try {
            Files.delete(target);
        } catch (IOException exception) {
            throw new EvidenceStorageFailureException("Unable to discard finalized evidence content");
        }
    }

    @Override
    public OpenedEvidence open(String storageKey) {
        String canonicalKey = EvidenceStorageKeyFactory.requireCanonical(storageKey);
        Path target = resolveCanonical(canonicalKey);
        rejectSymlinksInExistingPath(target);
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || !Files.isReadable(target)) {
                throw new EvidenceFileUnavailableException();
            }
            SeekableByteChannel channel = Files.newByteChannel(target, READ_NOFOLLOW);
            try {
                return new OpenedEvidence(canonicalKey, channel.size(), Channels.newInputStream(channel));
            } catch (IOException | RuntimeException exception) {
                closeAfterOpenFailure(channel);
                throw exception;
            }
        } catch (NoSuchFileException | EvidenceFileUnavailableException exception) {
            throw new EvidenceFileUnavailableException();
        } catch (IOException exception) {
            throw new EvidenceFileUnavailableException();
        }
    }

    private Path initializeRoot(Path configuredRoot) {
        Path absoluteRoot;
        try {
            absoluteRoot = configuredRoot.toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            throw new EvidenceStorageFailureException("Evidence storage root cannot be resolved");
        }
        ensureDirectory(absoluteRoot);
        ensureTrustedDirectory(absoluteRoot);
        try {
            Path realRoot = absoluteRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!realRoot.equals(absoluteRoot)) {
                throw new UnsafeEvidenceStoragePathException();
            }
            return realRoot;
        } catch (IOException exception) {
            throw new EvidenceStorageFailureException("Evidence storage root cannot be resolved");
        }
    }

    private void ensureDirectory(Path directory) {
        Path current = directory.getRoot();
        if (current == null) {
            throw new EvidenceStorageFailureException("Evidence storage directory cannot be resolved");
        }
        for (Path segment : directory) {
            current = current.resolve(segment);
            if (Files.exists(current, NOFOLLOW)) {
                if (Files.isSymbolicLink(current)) {
                    throw new UnsafeEvidenceStoragePathException();
                }
                if (!Files.isDirectory(current, NOFOLLOW)) {
                    throw new EvidenceStorageFailureException("Evidence storage location is not a directory");
                }
                continue;
            }
            try {
                Files.createDirectory(current);
            } catch (FileAlreadyExistsException exception) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, NOFOLLOW)) {
                    throw new UnsafeEvidenceStoragePathException();
                }
            } catch (IOException exception) {
                throw new EvidenceStorageFailureException("Evidence storage directory cannot be created");
            }
        }
    }

    private void ensureTrustedDirectory(Path directory) {
        rejectSymlinksInExistingPath(directory);
        if (!Files.isDirectory(directory, NOFOLLOW) || !Files.isWritable(directory)) {
            throw new EvidenceStorageFailureException("Evidence storage directory is unavailable");
        }
    }

    private Path createTemporaryFile() {
        try {
            return Files.createTempFile(stagingDirectory, "evidence-", ".tmp");
        } catch (IOException exception) {
            throw new EvidenceStorageFailureException("Unable to create staged evidence content");
        }
    }

    private void reserve(Path lock) {
        rejectSymlinksInExistingPath(lock);
        try (SeekableByteChannel ignored = Files.newByteChannel(
                lock, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
            // The sibling lock file is the cross-process reservation.
        } catch (FileAlreadyExistsException exception) {
            throw new EvidenceTargetExistsException();
        } catch (IOException exception) {
            throw new EvidenceStorageFailureException("Unable to reserve evidence storage target");
        }
    }

    private void ensureSafeParentDirectories(Path parent) {
        Path relative = trustedRoot.relativize(parent);
        Path current = trustedRoot;
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.exists(current, NOFOLLOW)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, NOFOLLOW)) {
                    throw new UnsafeEvidenceStoragePathException();
                }
                continue;
            }
            try {
                Files.createDirectory(current);
            } catch (FileAlreadyExistsException exception) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, NOFOLLOW)) {
                    throw new UnsafeEvidenceStoragePathException();
                }
            } catch (IOException exception) {
                throw new EvidenceStorageFailureException("Unable to create evidence storage directory");
            }
        }
    }

    private void rejectSymlinksInExistingPath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(trustedRoot == null ? normalized.getRoot() : trustedRoot)) {
            throw new UnsafeEvidenceStoragePathException();
        }
        Path current = normalized.getRoot();
        for (Path segment : normalized) {
            current = current.resolve(segment);
            if (Files.exists(current, NOFOLLOW) && Files.isSymbolicLink(current)) {
                throw new UnsafeEvidenceStoragePathException();
            }
        }
    }

    private Path resolveCanonical(String canonicalKey) {
        Path resolved = trustedRoot.resolve(canonicalKey).normalize();
        if (!resolved.startsWith(trustedRoot)) {
            throw new UnsafeEvidenceStoragePathException();
        }
        return resolved;
    }

    private void requireSafeStagedFile(Path temporary) {
        Path normalized = temporary.toAbsolutePath().normalize();
        if (!normalized.startsWith(stagingDirectory)
                || Files.isSymbolicLink(normalized)
                || !Files.isRegularFile(normalized, NOFOLLOW)) {
            throw new EvidenceStorageFailureException("Staged evidence content is unavailable");
        }
    }

    private FileSystemStagedEvidence requireOwned(StagedEvidence stagedEvidence) {
        if (!(stagedEvidence instanceof FileSystemStagedEvidence staged) || staged.owner() != owner) {
            throw new EvidenceStorageFailureException("Staged evidence content does not belong to this storage");
        }
        return staged;
    }

    private void cleanupStagedAfterFailure(Path temporary) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException exception) {
            throw new EvidenceStorageFailureException("Unable to clean up staged evidence content");
        }
    }

    private void cleanupFinalizeFailure(Path temporary) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException exception) {
            throw new EvidenceStorageFailureException("Unable to clean up evidence finalization");
        }
    }

    private void deleteReservation(Path lock) {
        try {
            Files.deleteIfExists(lock);
        } catch (IOException exception) {
            throw new EvidenceStorageFailureException("Unable to clean up evidence finalization");
        }
    }

    private void closeAfterOpenFailure(SeekableByteChannel channel) {
        try {
            channel.close();
        } catch (IOException exception) {
            throw new EvidenceFileUnavailableException();
        }
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new EvidenceStorageFailureException("SHA-256 is unavailable");
        }
    }

    private record FileSystemStagedEvidence(
            Object owner, String storageKey, long byteCount, String contentSha256, Path temporary)
            implements StagedEvidence {}
}
