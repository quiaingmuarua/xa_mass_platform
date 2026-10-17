package com.xa.mass.scenario.appchecks;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.function.Supplier;
import tools.jackson.databind.json.JsonMapper;

/** One local configuration owner. No Runtime identities or execution records are stored here. */
final class AppCheckConfigurationStore implements AutoCloseable {
    record Snapshot(String fingerprint, AppCheckSettings configuration) {
        Snapshot(AppCheckSettings configuration) { this(fingerprintOf(configuration), configuration); }
        Map<String, Object> toMap() { return Map.of("fingerprint", fingerprint, "configuration", configuration.toMap()); }
    }
    private final Path path;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final FilePublication files;
    private final Snapshot effective;
    private final AppCheckContracts contracts;
    private boolean closed;

    AppCheckConfigurationStore(Path path, Supplier<String> seed, AppCheckContracts contracts) {
        this(path, seed, contracts, new FilePublication());
    }

    AppCheckConfigurationStore(Path path, Supplier<String> seed, AppCheckContracts contracts, FilePublication files) {
        this.contracts = Objects.requireNonNull(contracts);
        this.files = Objects.requireNonNull(files);
        FileChannel opened = null;
        try {
            Path absolute = path.toAbsolutePath().normalize();
            Files.createDirectories(absolute.getParent());
            Path resolved = absolute.getParent().toRealPath().resolve(absolute.getFileName());
            this.path = Files.exists(resolved, java.nio.file.LinkOption.NOFOLLOW_LINKS) ? resolved.toRealPath() : resolved;
            // The lock is never renamed or deleted with the document: every process locks the same file.
            opened = FileChannel.open(this.path.resolveSibling(this.path.getFileName() + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lockChannel = opened;
            lock = opened.tryLock();
            if (lock == null) throw new IOException("Configuration file is already owned by another process");
            Snapshot existing;
            try { existing = read(); }
            catch (NoSuchFileException missing) {
                var initial = AppCheckSettings.parse(Jsons.parseObject(seed.get()), contracts);
                publish(initial);
                existing = read();
            }
            effective = existing;
        } catch (IOException | RuntimeException failure) {
            if (opened != null) try { opened.close(); } catch (IOException close) { failure.addSuppressed(close); }
            throw AppCheckConfigurationException.unavailable("open", failure);
        }
    }

    Snapshot effective() { return effective; }

    synchronized Map<String, Object> view() {
        try { return view(read()); }
        catch (IOException | RuntimeException failure) { throw AppCheckConfigurationException.unavailable("load", failure); }
    }

    synchronized Map<String, Object> save(String expectedFingerprint, Object document) {
        final AppCheckSettings settings;
        try {
            if (expectedFingerprint == null || !expectedFingerprint.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("expectedFingerprint 必须是读取到的 SHA-256 指纹");
            settings = AppCheckSettings.parse(document, contracts);
        }
        catch (IllegalArgumentException invalid) {
            throw new AppCheckConfigurationException(400, AppCheckConfigurationException.Code.INVALID_CONFIGURATION,
                    "save", invalid.getMessage(), invalid);
        }
        try {
            // The process lock and this instance's gate cover comparison and complete publication.
            var previous = read();
            if (!previous.fingerprint().equals(expectedFingerprint)) throw conflict();
            try { settings.requireUpdateOf(previous.configuration()); }
            catch (IllegalArgumentException invalid) {
                throw new AppCheckConfigurationException(400, AppCheckConfigurationException.Code.INVALID_CONFIGURATION,
                        "save", invalid.getMessage(), invalid);
            }
            var next = new Snapshot(settings);
            if (previous.fingerprint().equals(next.fingerprint())) return view(previous);
            publish(settings);
            return view(next);
        } catch (AppCheckConfigurationException known) { throw known;
        } catch (IOException | RuntimeException failure) {
            throw AppCheckConfigurationException.unavailable("save", failure);
        }
    }

    private Snapshot read() throws IOException {
        if (closed) throw new IOException("Store is closed");
        return new Snapshot(AppCheckSettings.parse(Jsons.parseObject(Files.readString(path, StandardCharsets.UTF_8)), contracts));
    }

    private void publish(AppCheckSettings settings) throws IOException {
        byte[] document = (JsonMapper.builder().build().writerWithDefaultPrettyPrinter()
                .writeValueAsString(canonical(settings.toMap())) + "\n").getBytes(StandardCharsets.UTF_8);
        Path pending = Files.createTempFile(path.getParent(), path.getFileName() + ".", ".tmp");
        try {
            files.write(pending, document);
            files.replace(pending, path);
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    /** Package-local file boundary for real-file publication fault proofs. No independent state or lifecycle. */
    static class FilePublication {
        void write(Path pending, byte[] document) throws IOException {
            try (var output = FileChannel.open(pending, StandardOpenOption.WRITE)) {
                var bytes = ByteBuffer.wrap(document);
                while (bytes.hasRemaining()) output.write(bytes);
                output.force(true);
            }
        }

        void replace(Path pending, Path target) throws IOException {
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String fingerprintOf(AppCheckSettings settings) {
        try {
            byte[] canonical = Jsons.toJson(canonical(settings.toMap())).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is required", impossible); }
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach((key, item) -> sorted.put((String) key, canonical(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(AppCheckConfigurationStore::canonical).toList();
        if (value instanceof Number number) return new BigDecimal(number.toString()).stripTrailingZeros();
        return value;
    }

    private Map<String, Object> view(Snapshot saved) {
        boolean changedWindow = effective.configuration().poolGroups().entrySet().stream().anyMatch(entry ->
                saved.configuration().poolGroups().containsKey(entry.getKey())
                        && saved.configuration().poolGroups().get(entry.getKey()).windowMillis() != entry.getValue().windowMillis());
        return Map.of("saved", saved.toMap(), "effective", effective.toMap(),
                "restartRequired", !saved.fingerprint().equals(effective.fingerprint()),
                "newScopeRequired", changedWindow, "checkTypes", contracts.descriptions());
    }

    private static AppCheckConfigurationException conflict() {
        return new AppCheckConfigurationException(409, AppCheckConfigurationException.Code.CONFIGURATION_CONFLICT,
                "save", "配置内容已变化，请重新读取后核对；本次未覆盖配置", null);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            try { lock.release(); }
            finally { lockChannel.close(); }
        } catch (IOException failure) { throw AppCheckConfigurationException.unavailable("close", failure); }
    }
}
