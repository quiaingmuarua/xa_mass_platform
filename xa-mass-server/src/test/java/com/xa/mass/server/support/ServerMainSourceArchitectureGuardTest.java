package com.xa.mass.server.support;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerMainSourceArchitectureGuardTest {

    private static final Path SERVER_MAIN_SOURCE_ROOT = Path.of("src/main/java");

    private static final Map<String, String> FORBIDDEN_IMPORT_FRAGMENTS = Map.of(
            "base", "import com.xa.mass.base.",
            "engine", "import com.xa.mass.engine.",
            "sdk.internal", "import com.xa.mass.sdk.internal."
    );

    @Test
    void serverMainSourceDoesNotImportBaseEngineOrSdkInternal() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(SERVER_MAIN_SOURCE_ROOT)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> collectViolations(path, violations));
        }

        assertTrue(violations.isEmpty(),
                "server main source must stay on sdk-owned surfaces only:\n" + String.join("\n", violations));
    }

    @Test
    void taskResultEndpointsDoNotUseProjectionRowsAsResultSource() throws IOException {
        Path controller = SERVER_MAIN_SOURCE_ROOT.resolve("com/xa/mass/api/internal/TaskApiController.java");
        String source = Files.readString(controller, StandardCharsets.UTF_8);

        assertTrue(!source.contains("TaskMessageProjection"),
                "TaskApiController result endpoints must use TaskResultQueryOperations, not TaskMessageProjection");
        assertTrue(!source.contains("getTaskMessageProjections"),
                "TaskApiController must not read TaskDetailStore projection rows for public results");
    }

    @Test
    void externalWorkerControllerDoesNotReintroduceWorkerContextCompatibilitySurface() throws IOException {
        Path controller = SERVER_MAIN_SOURCE_ROOT.resolve("com/xa/mass/api/internal/ExternalWorkerApiController.java");
        String source = Files.readString(controller, StandardCharsets.UTF_8);

        assertTrue(!source.contains("ExternalWorkerOperations"),
                "ExternalWorkerApiController must inject worker registry/client surfaces directly");
        assertTrue(!source.contains("WorkerContextCompatibilityOperations"),
                "ExternalWorkerApiController must not reintroduce WorkerContext compatibility routes");
    }

    @Test
    void kernelAndTransportDoNotImportServerIamStores() throws IOException {
        Path repoRoot = Path.of("..").toAbsolutePath().normalize();
        List<Path> scannedRoots = List.of(
                repoRoot.resolve("xa-mass-engine/src/main/java"),
                repoRoot.resolve("transport"),
                repoRoot.resolve("platform_infra")
        );
        List<String> violations = new ArrayList<>();
        for (Path root : scannedRoots) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> {
                            try {
                                String source = Files.readString(path, StandardCharsets.UTF_8);
                                if (source.contains("import com.xa.mass.api.auth.")) {
                                    violations.add(repoRoot.relativize(path) + " imports server IAM/auth store package");
                                }
                            } catch (IOException e) {
                                violations.add(path + " could not be read: " + e.getMessage());
                            }
                        });
            }
        }

        assertTrue(violations.isEmpty(),
                "IAM/auth stores must stay in server control-plane, not kernel/runtime/transport:\n"
                        + String.join("\n", violations));
    }

    private static void collectViolations(Path path, List<String> violations) {
        String source;
        try {
            source = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            violations.add(path + " could not be read: " + e.getMessage());
            return;
        }

        FORBIDDEN_IMPORT_FRAGMENTS.forEach((label, fragment) -> {
            if (source.contains(fragment)) {
                violations.add(path + " imports forbidden " + label + " type");
            }
        });
    }
}
