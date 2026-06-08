package com.xa.mass.server.config;

import com.xa.mass.storage.jdbc.JdbcStorageMode;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class LocalSchemaResetGuard {

    private static final Set<String> ALLOWLISTED_PROFILES = Set.of("durable-local");
    private static final List<String> HASH_RESOURCE_PATTERNS = List.of(
            "classpath*:db/migration/control-plane/**/*.sql",
            "classpath*:db/migration/server-control-plane/**/*.sql"
    );

    private final PathMatchingResourcePatternResolver resolver =
            new PathMatchingResourcePatternResolver();

    public void verify(String[] activeProfiles,
                       JdbcStorageMode mode,
                       String jdbcUrl,
                       boolean enabled,
                       boolean resetOnMismatch) {
        if (!enabled && !resetOnMismatch) {
            return;
        }
        boolean allowlistedProfile = Arrays.stream(activeProfiles)
                .map(profile -> profile.trim().toLowerCase(Locale.ROOT))
                .anyMatch(ALLOWLISTED_PROFILES::contains);
        if (!allowlistedProfile) {
            if (resetOnMismatch) {
                throw new IllegalStateException(
                        "mass.local-schema-reset.reset-on-mismatch=true is allowed only for "
                                + "allowlisted local profiles " + ALLOWLISTED_PROFILES);
            }
            return;
        }
        if (mode != JdbcStorageMode.JDBC_SQLITE) {
            if (resetOnMismatch) {
                throw new IllegalStateException(
                        "mass.local-schema-reset.reset-on-mismatch=true is allowed only for "
                                + "durable-local SQLite file targets");
            }
            return;
        }
        Path dbPath = localSqlitePath(jdbcUrl);
        if (dbPath == null) {
            if (resetOnMismatch) {
                throw new IllegalStateException(
                        "mass.local-schema-reset.reset-on-mismatch=true requires a local "
                                + "file-backed SQLite JDBC URL");
            }
            return;
        }

        String currentHash = currentSchemaHash();
        Path metadataPath = metadataPath(dbPath);
        boolean dbExists = Files.exists(dbPath);
        boolean metadataExists = Files.exists(metadataPath);

        if (!dbExists && !metadataExists) {
            writeMetadata(metadataPath, currentHash);
            return;
        }
        if (!metadataExists) {
            handleMismatch(dbPath, metadataPath, "<missing>", currentHash, resetOnMismatch);
            return;
        }

        String recordedHash = readMetadata(metadataPath);
        if (!currentHash.equals(recordedHash)) {
            handleMismatch(dbPath, metadataPath, recordedHash, currentHash, resetOnMismatch);
        }
    }

    private void handleMismatch(Path dbPath,
                                Path metadataPath,
                                String oldHash,
                                String newHash,
                                boolean resetOnMismatch) {
        if (!resetOnMismatch) {
            throw new IllegalStateException(
                    "Local SQLite schema fingerprint mismatch for " + dbPath
                            + " old=" + oldHash
                            + " new=" + newHash
                            + ". Delete/reseed this local DB or set "
                            + "mass.local-schema-reset.reset-on-mismatch=true for an "
                            + "allowlisted local SQLite target.");
        }
        deleteIfExists(dbPath);
        deleteIfExists(metadataPath);
        writeMetadata(metadataPath, newHash);
    }

    private String currentSchemaHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("xa-mass-local-schema-reset-v1\n".getBytes(StandardCharsets.UTF_8));
            for (String pattern : HASH_RESOURCE_PATTERNS) {
                Resource[] resources = resolver.getResources(pattern);
                Arrays.sort(resources, (left, right) -> resourceName(left).compareTo(resourceName(right)));
                digest.update(("pattern:" + pattern + "\n").getBytes(StandardCharsets.UTF_8));
                for (Resource resource : resources) {
                    digest.update(("resource:" + resourceName(resource) + "\n").getBytes(StandardCharsets.UTF_8));
                    try (InputStream input = resource.getInputStream()) {
                        input.transferTo(new DigestOutputStreamAdapter(digest));
                    }
                    digest.update((byte) '\n');
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read schema resources for local reset fingerprint", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String resourceName(Resource resource) {
        String filename = resource.getFilename();
        return filename == null ? resource.getDescription() : filename;
    }

    private static Path localSqlitePath(String jdbcUrl) {
        String prefix = "jdbc:sqlite:";
        if (jdbcUrl == null || !jdbcUrl.startsWith(prefix)) {
            return null;
        }
        String rawPath = jdbcUrl.substring(prefix.length()).trim();
        if (rawPath.isBlank() || rawPath.equals(":memory:") || rawPath.startsWith("file::memory:")) {
            return null;
        }
        int queryIndex = rawPath.indexOf('?');
        if (queryIndex >= 0) {
            rawPath = rawPath.substring(0, queryIndex);
        }
        if (rawPath.startsWith("file:")) {
            rawPath = rawPath.substring("file:".length());
        }
        return Paths.get(rawPath).toAbsolutePath().normalize();
    }

    private static Path metadataPath(Path dbPath) {
        return dbPath.resolveSibling(dbPath.getFileName() + ".schema.sha256");
    }

    private static String readMetadata(Path metadataPath) {
        try {
            return Files.readString(metadataPath, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read local schema fingerprint: " + metadataPath, e);
        }
    }

    private static void writeMetadata(Path metadataPath, String hash) {
        try {
            Path parent = metadataPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(metadataPath, hash + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write local schema fingerprint: " + metadataPath, e);
        }
    }

    private static void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete local schema reset target: " + path, e);
        }
    }

    private static final class DigestOutputStreamAdapter extends java.io.OutputStream {
        private final MessageDigest digest;

        private DigestOutputStreamAdapter(MessageDigest digest) {
            this.digest = digest;
        }

        @Override
        public void write(int value) {
            digest.update((byte) value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            digest.update(bytes, offset, length);
        }
    }
}
