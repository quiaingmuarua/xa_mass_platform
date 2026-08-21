package com.xa.mass.server.kernelpacer;

import com.xa.mass.server.kernelredis.KernelRedisProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.json.JsonMapper;

final class PythonKernelPacerProcess {

    static final String MODULE =
            "kernel_design.executable_spec.assembly";
    static final String REDIS_URL_ENV =
            "XA_MASS_KERNEL_PACER_REDIS_URL";
    static final String REDIS_PREFIX_ENV =
            "XA_MASS_KERNEL_PACER_REDIS_PREFIX";
    private static final String OWNER_FILE_NAME = "owner.json";
    private static final String READY_FILE_NAME = "ready";
    private static final long READY_POLL_MILLIS = 25;
    private static final System.Logger LOGGER = System.getLogger(
            PythonKernelPacerProcess.class.getName()
    );

    private final KernelPacerProperties properties;
    private final KernelRedisProperties redisProperties;
    private final JsonMapper json;
    private final Path workingDirectory;
    private final Path configPath;
    private final Path stateDirectory;
    private final Path ownerFile;
    private final Path readyFile;

    private Process process;
    private String instanceToken;

    PythonKernelPacerProcess(
            KernelPacerProperties properties,
            KernelRedisProperties redisProperties,
            JsonMapper json
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.redisProperties = Objects.requireNonNull(
                redisProperties,
                "redisProperties"
        );
        this.json = Objects.requireNonNull(json, "json");
        this.workingDirectory = Path.of(properties.workingDirectory())
                .toAbsolutePath()
                .normalize();
        this.configPath = resolve(Path.of(properties.configPath()));
        this.stateDirectory = resolve(Path.of(properties.stateDirectory()));
        this.ownerFile = stateDirectory.resolve(OWNER_FILE_NAME);
        this.readyFile = stateDirectory.resolve(READY_FILE_NAME);
    }

    synchronized void start() {
        if (process != null) {
            throw new IllegalStateException(
                    "operation=kernelPacer.start process is already owned"
            );
        }
        validateFiles();
        try {
            Files.createDirectories(stateDirectory);
            prepareHistoricalState();
            Files.deleteIfExists(readyFile);
        } catch (IOException error) {
            throw failure("prepare", error);
        }

        String token = UUID.randomUUID().toString();
        Process started = null;
        try {
            started = createProcess(token);
            process = started;
            instanceToken = token;
            writeOwner(started, token);
            awaitReady(started, token);
        } catch (IOException error) {
            stopFailedStart(started);
            throw failure("start", error);
        } catch (RuntimeException error) {
            stopFailedStart(started);
            throw error;
        }
    }

    synchronized void stop() {
        Process owned = process;
        if (owned == null) {
            return;
        }
        String token = instanceToken;
        closeInput(owned);
        if (!awaitExit(owned, properties.shutdownTimeout())) {
            owned.destroyForcibly();
            if (!awaitExit(owned, Duration.ofSeconds(1))
                    && owned.isAlive()) {
                throw new IllegalStateException(
                        "operation=kernelPacer.stop child did not exit"
                );
            }
        }
        process = null;
        instanceToken = null;
        deleteOwnedReady(token);
        deleteQuietly(ownerFile);
    }

    synchronized boolean isAlive() {
        return process != null && process.isAlive();
    }

    synchronized Long pid() {
        return process == null ? null : process.pid();
    }

    private Process createProcess(String token) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(properties.pythonExecutable());
        command.add("-u");
        command.add("-m");
        command.add(MODULE);
        command.add("--config");
        command.add(configPath.toString());
        command.add("--instance-token");
        command.add(token);
        command.add("--ready-file");
        command.add(readyFile.toString());
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT);
        builder.environment().put(
                REDIS_URL_ENV,
                redisProperties.redisUrl().toString()
        );
        builder.environment().put(
                REDIS_PREFIX_ENV,
                redisProperties.redisPrefix()
        );
        return builder.start();
    }

    private void awaitReady(Process started, String token) {
        long deadline = System.nanoTime()
                + properties.startupTimeout().toNanos();
        while (System.nanoTime() < deadline) {
            if (!started.isAlive()) {
                throw new IllegalStateException(
                        "operation=kernelPacer.start child exited with code "
                                + started.exitValue()
                );
            }
            if (readyTokenEquals(token)) {
                return;
            }
            try {
                Thread.sleep(READY_POLL_MILLIS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw failure("start", error);
            }
        }
        throw new IllegalStateException(
                "operation=kernelPacer.start child readiness timed out"
        );
    }

    private boolean readyTokenEquals(String token) {
        try {
            return Files.exists(readyFile)
                    && token.equals(Files.readString(
                            readyFile,
                            StandardCharsets.UTF_8
                    ));
        } catch (IOException ignored) {
            return false;
        }
    }

    void prepareHistoricalState() throws IOException {
        if (!Files.exists(ownerFile)) {
            return;
        }
        OwnerRecord owner;
        try {
            owner = json.readValue(
                    Files.readString(ownerFile, StandardCharsets.UTF_8),
                    OwnerRecord.class
            );
        } catch (RuntimeException error) {
            throw failure("recoverOwner", error);
        }
        ProcessHandle handle = ProcessHandle.of(owner.pid()).orElse(null);
        if (handle == null || !handle.isAlive()) {
            clearHistoricalState();
            return;
        }
        Instant recordedStart;
        try {
            recordedStart = Instant.parse(owner.processStartInstant());
        } catch (RuntimeException error) {
            throw failure("recoverOwner", error);
        }
        Instant actualStart = handle.info().startInstant().orElse(null);
        if (actualStart == null) {
            throw new IllegalStateException(
                    "operation=kernelPacer.recoverOwner live process identity"
                            + " cannot be verified"
            );
        }
        if (!actualStart.equals(recordedStart)) {
            clearHistoricalState();
            return;
        }
        throw new IllegalStateException(
                "operation=kernelPacer.recoverOwner existing managed process"
                        + " is still running"
        );
    }

    private void writeOwner(Process started, String token) throws IOException {
        Instant start = started.info().startInstant().orElseThrow(
                () -> new IllegalStateException(
                        "operation=kernelPacer.start child start time unavailable"
                )
        );
        OwnerRecord owner = new OwnerRecord(
                started.pid(),
                start.toString(),
                token,
                configPath.toString(),
                MODULE,
                started.info().command().orElse(properties.pythonExecutable())
        );
        writeAtomically(ownerFile, json.writeValueAsString(owner));
    }

    private void clearHistoricalState() throws IOException {
        Files.deleteIfExists(ownerFile);
        Files.deleteIfExists(readyFile);
    }

    private static void writeAtomically(Path target, String content)
            throws IOException {
        Path temporary = target.resolveSibling("." + target.getFileName()
                + ".tmp");
        Files.writeString(
                temporary,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private void validateFiles() {
        if (!Files.isDirectory(workingDirectory)) {
            throw new IllegalArgumentException(
                    "workingDirectory must be an existing directory"
            );
        }
        if (!Files.isRegularFile(configPath) || !Files.isReadable(configPath)) {
            throw new IllegalArgumentException(
                    "configPath must be a readable regular file"
            );
        }
    }

    private Path resolve(Path path) {
        return path.isAbsolute()
                ? path.normalize()
                : workingDirectory.resolve(path).normalize();
    }

    private void stopFailedStart(Process started) {
        boolean stopped = true;
        if (started != null) {
            closeInput(started);
            if (started.isAlive()) {
                started.destroyForcibly();
                stopped = awaitExit(started, Duration.ofSeconds(1))
                        || !started.isAlive();
            }
        }
        if (!stopped) {
            return;
        }
        process = null;
        String token = instanceToken;
        instanceToken = null;
        deleteOwnedReady(token);
        deleteQuietly(ownerFile);
    }

    private void deleteOwnedReady(String token) {
        if (token == null) {
            return;
        }
        try {
            if (readyTokenEquals(token)) {
                Files.deleteIfExists(readyFile);
            }
        } catch (IOException error) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "operation=kernelPacer.cleanupReady failed",
                    error
            );
        }
    }

    private static void closeInput(Process owned) {
        try {
            owned.getOutputStream().close();
        } catch (IOException ignored) {
            // The child may already have exited.
        }
    }

    private static boolean awaitExit(Process owned, Duration timeout) {
        try {
            return owned.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException error) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "operation=kernelPacer.cleanupOwner failed",
                    error
            );
        }
    }

    private static IllegalStateException failure(
            String operation,
            Exception cause
    ) {
        return new IllegalStateException(
                "operation=kernelPacer." + operation + " failed",
                cause
        );
    }

    private record OwnerRecord(
            long pid,
            String processStartInstant,
            String instanceToken,
            String configPath,
            String module,
            String executableCommand
    ) {
        private OwnerRecord {
            if (pid <= 0) {
                throw new IllegalArgumentException("pid must be positive");
            }
            Objects.requireNonNull(
                    processStartInstant,
                    "processStartInstant"
            );
            if (instanceToken == null || instanceToken.isBlank()) {
                throw new IllegalArgumentException(
                        "instanceToken must be non-empty"
                );
            }
            Objects.requireNonNull(configPath, "configPath");
            Objects.requireNonNull(module, "module");
            Objects.requireNonNull(executableCommand, "executableCommand");
        }
    }
}
