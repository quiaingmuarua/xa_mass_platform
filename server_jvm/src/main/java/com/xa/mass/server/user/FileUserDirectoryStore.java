package com.xa.mass.server.user;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/**
 * Package-local file-backed {@link UserDirectoryStore} for legacy {@code file:} seed persistence.
 *
 * <p>Owns the target JSON file exclusively via an in-process path reservation and a sibling {@code .lock}
 * file lock, detects out-of-band modifications by SHA-256 fingerprint, and publishes updates atomically.
 */
final class FileUserDirectoryStore implements UserDirectoryStore {
    // Reserve a canonical path before opening its lock channel; the JVM rejects overlapping locks itself.
    private static final Set<Path> OWNED_FILES = ConcurrentHashMap.newKeySet();

    private final ObjectMapper mapper;
    private final Path sourcePath;
    private final FilePublication publication;
    private @Nullable Path canonicalFile;
    private @Nullable FileChannel lockChannel;
    private String fingerprint = "";
    private boolean closed;

    FileUserDirectoryStore(ObjectMapper mapper, Path sourcePath) {
        this(mapper, sourcePath, new FilePublication());
    }

    FileUserDirectoryStore(ObjectMapper mapper, Path sourcePath, FilePublication publication) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
        this.publication = Objects.requireNonNull(publication, "publication");
    }

    @Override
    public UserDirectory.Seed load(UnaryOperator<UserDirectory.Seed> validateSeed) throws IOException {
        Path reserved = null;
        FileChannel opened = null;
        try {
            Path absolute = sourcePath.toAbsolutePath().normalize();
            Path canonical = absolute.getParent().toRealPath().resolve(absolute.getFileName());
            if (Files.exists(canonical, LinkOption.NOFOLLOW_LINKS)) {
                canonical = canonical.toRealPath();
            }
            if (!OWNED_FILES.add(canonical)) {
                throw new IOException("User directory file is already owned by this process");
            }
            reserved = canonical;
            // The lock file is never renamed with the document, so every process locks the same file.
            opened = FileChannel.open(canonical.resolveSibling(canonical.getFileName() + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = opened.tryLock();
            if (lock == null) {
                throw new IOException("User directory file is already owned by another process");
            }
            byte[] bytes = Files.readAllBytes(canonical);
            UserDirectory.Seed validated = validateSeed.apply(UserDirectoryStore.parseSeed(mapper, bytes));
            this.canonicalFile = canonical;
            this.lockChannel = opened;
            this.fingerprint = UserDirectory.sha256Hex(bytes);
            return validated;
        } catch (IOException | RuntimeException | Error failure) {
            if (opened != null) {
                try {
                    opened.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (reserved != null) {
                OWNED_FILES.remove(reserved);
            }
            throw failure;
        }
    }

    @Override
    public void createUser(UserDirectory.User user, @Nullable String accessKeySha256, UserDirectory.Seed nextSeed)
            throws IOException {
        publish(nextSeed);
    }

    @Override
    public void updateUser(UserDirectory.User user, UserDirectory.Seed nextSeed) throws IOException {
        publish(nextSeed);
    }

    @Override
    public void updateAccessKeyHash(String userId, @Nullable String accessKeySha256, UserDirectory.Seed nextSeed)
            throws IOException {
        publish(nextSeed);
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        if (lockChannel == null) {
            return;
        }
        try {
            lockChannel.close();
        } finally {
            if (canonicalFile != null) {
                OWNED_FILES.remove(canonicalFile);
            }
        }
    }

    private void publish(UserDirectory.Seed nextSeed) throws IOException {
        Path file = Objects.requireNonNull(canonicalFile, "FileUserDirectoryStore has not been loaded");
        byte[] document = UserDirectoryStore.renderSeed(mapper, nextSeed);
        if (!UserDirectory.sha256Hex(Files.readAllBytes(file)).equals(fingerprint)) {
            throw new ConflictException("User directory file changed outside this Server");
        }
        Path pending = Files.createTempFile(file.getParent(), file.getFileName() + ".", ".tmp");
        try {
            publication.write(pending, document);
            publication.replace(pending, file);
        } finally {
            Files.deleteIfExists(pending);
        }
        fingerprint = UserDirectory.sha256Hex(document);
    }

    /** Package-local file boundary for publication fault proofs. No independent state or lifecycle. */
    static class FilePublication {
        void write(Path pending, byte[] document) throws IOException {
            try (var output = FileChannel.open(pending, StandardOpenOption.WRITE)) {
                var bytes = ByteBuffer.wrap(document);
                while (bytes.hasRemaining()) {
                    output.write(bytes);
                }
                output.force(true);
            }
        }

        void replace(Path pending, Path target) throws IOException {
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
