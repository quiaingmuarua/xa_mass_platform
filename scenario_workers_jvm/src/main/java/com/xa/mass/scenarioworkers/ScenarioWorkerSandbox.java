package com.xa.mass.scenarioworkers;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class ScenarioWorkerSandbox implements AutoCloseable {

    private static final int SANDBOX_INVALID = 14013;
    private static final int SANDBOX_UNAVAILABLE = 14014;
    private static final int SANDBOX_PERSIST_FAILED = 14015;
    private static final String IDENTITY_FILE = "identity.json";
    private static final String PROPERTIES_FILE = "worker-properties.json";
    private static final String LOCK_FILE = "worker.lock";
    private static final Set<String> IDENTITY_FIELDS = Set.of(
            "workerGroupId",
            "clientWorkerKey",
            "workerId"
    );

    private final Path directory;
    private final String workerGroupId;
    private final String clientWorkerKey;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Map<String, Object> workerProperties;
    private String workerId;
    private boolean closed;

    private ScenarioWorkerSandbox(
            Path directory,
            String workerGroupId,
            String clientWorkerKey,
            FileChannel lockChannel,
            FileLock lock,
            Map<String, Object> workerProperties,
            String workerId
    ) {
        this.directory = directory;
        this.workerGroupId = workerGroupId;
        this.clientWorkerKey = clientWorkerKey;
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.workerProperties = workerProperties;
        this.workerId = workerId;
    }

    static ScenarioWorkerSandbox open(
            Path directory,
            String workerGroupId,
            String clientWorkerKey,
            Map<String, Object> initialWorkerProperties
    ) {
        Path normalized = directory.toAbsolutePath().normalize();
        FileChannel channel = null;
        FileLock lock = null;
        try {
            Files.createDirectories(normalized);
            channel = FileChannel.open(
                    normalized.resolve(LOCK_FILE),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
            );
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException error) {
                throw unavailable(normalized, error);
            }
            if (lock == null) {
                throw unavailable(normalized, null);
            }

            Map<String, Object> properties = loadOrCreateProperties(
                    normalized,
                    initialWorkerProperties
            );
            String workerId = loadIdentity(
                    normalized,
                    workerGroupId,
                    clientWorkerKey
            );
            return new ScenarioWorkerSandbox(
                    normalized,
                    workerGroupId,
                    clientWorkerKey,
                    channel,
                    lock,
                    properties,
                    workerId
            );
        } catch (ScenarioWorkerAssemblyException error) {
            closeQuietly(lock, channel);
            throw error;
        } catch (IOException error) {
            closeQuietly(lock, channel);
            throw new ScenarioWorkerAssemblyException(
                    SANDBOX_UNAVAILABLE,
                    "scenarioWorkerSandbox.open",
                    "Scenario Worker sandbox is unavailable: "
                            + normalized,
                    error
            );
        }
    }

    Map<String, Object> workerProperties() {
        return workerProperties;
    }

    Optional<String> workerId() {
        return Optional.ofNullable(workerId);
    }

    void storeWorkerId(String value) {
        requireOpen();
        String canonical = requireCanonicalWorkerId(
                value,
                "scenarioWorkerSandbox.storeIdentity"
        );
        if (workerId != null) {
            if (!workerId.equals(canonical)) {
                throw new ScenarioWorkerAssemblyException(
                        SANDBOX_INVALID,
                        "scenarioWorkerSandbox.storeIdentity",
                        "Scenario Worker sandbox already contains a different workerId"
                );
            }
            return;
        }

        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("workerGroupId", workerGroupId);
        identity.put("clientWorkerKey", clientWorkerKey);
        identity.put("workerId", canonical);
        writeJson(
                directory.resolve(IDENTITY_FILE),
                identity,
                "scenarioWorkerSandbox.storeIdentity"
        );
        workerId = canonical;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        IOException failure = null;
        try {
            lock.release();
        } catch (IOException error) {
            failure = error;
        }
        try {
            lockChannel.close();
        } catch (IOException error) {
            if (failure == null) {
                failure = error;
            } else {
                failure.addSuppressed(error);
            }
        }
        if (failure != null) {
            throw new ScenarioWorkerAssemblyException(
                    SANDBOX_UNAVAILABLE,
                    "scenarioWorkerSandbox.close",
                    "Could not release Scenario Worker sandbox: "
                            + directory,
                    failure
            );
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "Scenario Worker sandbox is closed"
            );
        }
    }

    private static Map<String, Object> loadOrCreateProperties(
            Path directory,
            Map<String, Object> initialWorkerProperties
    ) {
        Path path = directory.resolve(PROPERTIES_FILE);
        if (!Files.exists(path)) {
            writeJson(
                    path,
                    initialWorkerProperties,
                    "scenarioWorkerSandbox.storeProperties"
            );
        }
        try {
            return Collections.unmodifiableMap(
                    new LinkedHashMap<>(Jsons.parseObject(
                            Files.readString(path, StandardCharsets.UTF_8)
                    ))
            );
        } catch (IOException error) {
            throw new ScenarioWorkerAssemblyException(
                    SANDBOX_UNAVAILABLE,
                    "scenarioWorkerSandbox.loadProperties",
                    "Could not read Scenario Worker Properties from "
                            + path,
                    error
            );
        } catch (IllegalArgumentException error) {
            throw new ScenarioWorkerAssemblyException(
                    SANDBOX_INVALID,
                    "scenarioWorkerSandbox.loadProperties",
                    "Scenario Worker Properties are invalid in " + path,
                    error
            );
        }
    }

    private static String loadIdentity(
            Path directory,
            String workerGroupId,
            String clientWorkerKey
    ) {
        Path path = directory.resolve(IDENTITY_FILE);
        if (!Files.exists(path)) {
            return null;
        }
        Map<String, Object> identity;
        try {
            identity = Jsons.parseObject(
                    Files.readString(path, StandardCharsets.UTF_8)
            );
        } catch (IOException error) {
            throw new ScenarioWorkerAssemblyException(
                    SANDBOX_UNAVAILABLE,
                    "scenarioWorkerSandbox.loadIdentity",
                    "Could not read Scenario Worker identity from " + path,
                    error
            );
        } catch (IllegalArgumentException error) {
            throw invalidIdentity(path, error);
        }
        if (!identity.keySet().equals(IDENTITY_FIELDS)
                || !(identity.get("workerGroupId") instanceof String)
                || !(identity.get("clientWorkerKey") instanceof String)
                || !(identity.get("workerId") instanceof String)
                || !workerGroupId.equals(identity.get("workerGroupId"))
                || !clientWorkerKey.equals(identity.get("clientWorkerKey"))) {
            throw invalidIdentity(path, null);
        }
        return requireCanonicalWorkerId(
                (String) identity.get("workerId"),
                "scenarioWorkerSandbox.loadIdentity"
        );
    }

    private static void writeJson(
            Path target,
            Map<String, Object> value,
            String operation
    ) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(
                    target.getParent(),
                    target.getFileName().toString() + ".",
                    ".tmp"
            );
            Files.writeString(
                    temporary,
                    Jsons.toJson(value),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } catch (IOException | IllegalArgumentException error) {
            throw new ScenarioWorkerAssemblyException(
                    SANDBOX_PERSIST_FAILED,
                    operation,
                    "Could not persist Scenario Worker sandbox file "
                            + target,
                    error
            );
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The target write failure remains the primary error.
                }
            }
        }
    }

    private static String requireCanonicalWorkerId(
            String value,
            String operation
    ) {
        try {
            if (value == null
                    || !UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException();
            }
            return value;
        } catch (IllegalArgumentException error) {
            throw new ScenarioWorkerAssemblyException(
                    SANDBOX_INVALID,
                    operation,
                    "Scenario Worker identity contains an invalid workerId",
                    error
            );
        }
    }

    private static ScenarioWorkerAssemblyException invalidIdentity(
            Path path,
            Throwable cause
    ) {
        return new ScenarioWorkerAssemblyException(
                SANDBOX_INVALID,
                "scenarioWorkerSandbox.loadIdentity",
                "Scenario Worker identity is invalid in " + path,
                cause
        );
    }

    private static ScenarioWorkerAssemblyException unavailable(
            Path directory,
            Throwable cause
    ) {
        return new ScenarioWorkerAssemblyException(
                SANDBOX_UNAVAILABLE,
                "scenarioWorkerSandbox.open",
                "Scenario Worker sandbox is already in use: " + directory,
                cause
        );
    }

    private static void closeQuietly(
            FileLock lock,
            FileChannel channel
    ) {
        if (lock != null) {
            try {
                lock.release();
            } catch (IOException ignored) {
                // Preserve the sandbox initialization failure.
            }
        }
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // Preserve the sandbox initialization failure.
            }
        }
    }
}
