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
