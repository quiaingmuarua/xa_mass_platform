package com.xa.mass.storage.contract;

import com.xa.mass.storage.api.TaskShellStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageBoundaryGuardTest {

    private static final Pattern RUNTIME_OR_HISTORY_METHOD = Pattern.compile(
            "(?i)(schedul|dispatch|lease|heartbeat|history|analytics|attempt|reservation|runtime)"
    );

    private static final Pattern LEGACY_BROAD_STORAGE_SURFACE = Pattern.compile(String.join("|", List.of(
            "\\bTaskStorage\\b",
            "\\bWorkerStorage\\b",
            "\\bInMemoryTaskStorage\\b",
            "\\bInMemoryWorkerStorage\\b",
            "\\bJdbcTaskStorage\\b",
            "\\btaskStorage\\s*\\(",
            "\\bworkerStorage\\s*\\(",
            "\\bsetTaskStorage\\s*\\(",
            "\\bsetWorkerStorage\\s*\\(",
            "\\bgetTaskStorage\\s*\\(",
            "\\bgetWorkerStorage\\s*\\("
    )));
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)\\bCREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-zA-Z0-9_]+)"
    );
    private static final Pattern WORKER_RUNTIME_OR_HISTORY_TABLE = Pattern.compile(
            "(?i)(worker.*(heartbeat|dispatch|attempt|history|lease|runtime|reservation))"
                    + "|((heartbeat|dispatch|attempt|history|lease|runtime|reservation).*worker)"
    );

    @Test
    void taskShellStoreDoesNotGrowRuntimeOrHistoryMethods() {
        List<String> violations = new ArrayList<>();
        for (java.lang.reflect.Method method : TaskShellStore.class.getDeclaredMethods()) {
            String methodName = method.getName();
            if (RUNTIME_OR_HISTORY_METHOD.matcher(methodName).find()) {
                violations.add(TaskShellStore.class.getSimpleName() + "." + methodName);
            }
        }

        assertTrue(violations.isEmpty(),
                "Task shell store must not grow runtime/history-shaped methods:\n"
                        + String.join("\n", violations));
    }

    @Test
    void storageApiDoesNotReintroduceWorkerDeclarationContracts() throws IOException {
        Path storageApiRoot = repoRoot().resolve("platform_infra/mass-storage-api/src/main/java");
        List<String> violations = new ArrayList<>();
        for (Path path : javaSourceFiles(storageApiRoot)) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            if (source.contains("WorkerDeclarationStore") || source.contains("WorkerDeclarationRecord")) {
                violations.add(path.toString());
            }
        }

        assertTrue(violations.isEmpty(),
                "mass-storage-api must not reintroduce worker declaration contracts; "
                        + "worker declaration ports belong to xa-mass-worker-runtime:\n"
                        + String.join("\n", violations));
    }

    @Test
    void productionSourcesDoNotReintroduceBroadStorageVocabulary() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path root : productionSourceRoots()) {
            for (Path path : javaSourceFiles(root)) {
                String source = Files.readString(path, StandardCharsets.UTF_8);
                if (LEGACY_BROAD_STORAGE_SURFACE.matcher(source).find()) {
                    violations.add(path.toString());
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Production code must expose task shell and worker declaration surfaces, not broad "
                        + "TaskStorage/WorkerStorage vocabulary:\n"
                        + String.join("\n", violations));
    }

    @Test
    void jdbcMigrationsDoNotCreateWorkerRuntimeOrHistoryTables() throws IOException {
        Path migrationRoot = repoRoot().resolve(
                "platform_infra/mass-storage-jdbc/src/main/resources/db/migration");
        List<String> violations = new ArrayList<>();
        for (Path path : sqlFiles(migrationRoot)) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            java.util.regex.Matcher matcher = CREATE_TABLE.matcher(source);
            while (matcher.find()) {
                String tableName = matcher.group(1);
                if (WORKER_RUNTIME_OR_HISTORY_TABLE.matcher(tableName).find()) {
                    violations.add(path + " creates worker runtime/history table " + tableName);
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "JDBC storage may persist control-plane declarations, but worker heartbeat, dispatch, "
                        + "lease, reservation, and history tables belong to runtime or trace/archive ownership:\n"
                        + String.join("\n", violations));
    }

    private static List<Path> productionSourceRoots() {
        Path repoRoot = repoRoot();
        return List.of(
                repoRoot.resolve("platform_infra/mass-storage-api/src/main/java"),
                repoRoot.resolve("platform_infra/mass-storage-memory/src/main/java"),
                repoRoot.resolve("platform_infra/mass-storage-jdbc/src/main/java"),
                repoRoot.resolve("xa-mass-engine/src/main/java"),
                repoRoot.resolve("xa-mass-sdk/src/main/java"),
                repoRoot.resolve("xa-mass-server/src/main/java"),
                repoRoot.resolve("xa-mass-worker-runtime/src/main/java"),
                repoRoot.resolve("transport"),
                repoRoot.resolve("integrations")
        );
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("platform_infra"))
                    && Files.isDirectory(current.resolve("xa-mass-engine"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate repository root");
    }

    private static List<Path> javaSourceFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains(Path.of("src", "main", "java").toString()))
                    .toList();
        }
    }

    private static List<Path> sqlFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".sql"))
                    .toList();
        }
    }
}
