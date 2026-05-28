package com.xa.mass.storage.contract;

import com.xa.mass.storage.api.TaskShellStore;
import com.xa.mass.storage.api.WorkerDeclarationRecord;
import com.xa.mass.storage.api.WorkerDeclarationStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageBoundaryGuardTest {

    private static final Pattern RUNTIME_OR_HISTORY_METHOD = Pattern.compile(
            "(?i)(schedul|dispatch|lease|heartbeat|history|analytics|attempt|reservation|runtime)"
    );

    private static final Map<Class<?>, Set<String>> KNOWN_TWH_2_RESIDUE = Map.of(
            TaskShellStore.class, Set.of(),
            WorkerDeclarationStore.class, Set.of()
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

    @Test
    void shellAndDeclarationStoresDoNotGrowRuntimeOrHistoryMethods() {
        List<String> violations = new ArrayList<>();
        for (Class<?> contract : List.of(TaskShellStore.class, WorkerDeclarationStore.class)) {
            Set<String> knownResidue = KNOWN_TWH_2_RESIDUE.getOrDefault(contract, Set.of());
            for (Method method : contract.getDeclaredMethods()) {
                String methodName = method.getName();
                if (knownResidue.contains(methodName)) {
                    continue;
                }
                if (RUNTIME_OR_HISTORY_METHOD.matcher(methodName).find()) {
                    violations.add(contract.getSimpleName() + "." + methodName);
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Task shell and worker declaration stores must not grow runtime/history-shaped methods. "
                        + "Known TWH-2 residue is explicitly allowlisted until that slice removes it:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerDeclarationStoreDoesNotExposeBaseWorkerModel() {
        List<String> violations = new ArrayList<>();
        for (Method method : WorkerDeclarationStore.class.getDeclaredMethods()) {
            if (method.getReturnType().getName().equals("com.xa.mass.base.model.Worker")) {
                violations.add(method.getName() + " returns base.model.Worker");
            }
            for (Class<?> parameterType : method.getParameterTypes()) {
                if (parameterType.getName().equals("com.xa.mass.base.model.Worker")) {
                    violations.add(method.getName() + " accepts base.model.Worker");
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "WorkerDeclarationStore must persist WorkerDeclarationRecord, not the mixed base Worker model:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerDeclarationRecordDoesNotCarryRuntimeOrCapabilityHintFields() {
        Set<String> forbiddenComponents = Set.of(
                "statusName",
                "status",
                "lastHeartbeat",
                "supportedProjects",
                "supportedEventCodes",
                "dispatchEnabled",
                "reservedPermits",
                "exclusiveLeaseHeld"
        );

        List<String> violations = Stream.of(WorkerDeclarationRecord.class.getRecordComponents())
                .map(component -> component.getName())
                .filter(forbiddenComponents::contains)
                .toList();

        assertTrue(violations.isEmpty(),
                "WorkerDeclarationRecord must stay declaration-only and must not carry runtime state "
                        + "or worker-level capability hints:\n"
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
}
