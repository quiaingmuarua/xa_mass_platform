package com.xa.mass.scenario;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioLauncherArchitectureGuardTest {
    private static final List<String> FORBIDDEN_IMPORT_PREFIXES = List.of(
            "import com.xa.mass.server.",
            "import com.xa.mass.engine.",
            "import com.xa.mass.runtime.",
            "import com.xa.mass.workerpack."
    );

    @Test
    void productionCodeDoesNotImportPlatformInternalsOrWorkerPack() throws IOException {
        List<String> violations = mainJavaFiles().stream()
                .flatMap(path -> forbiddenImportViolations(path).stream())
                .toList();

        assertTrue(violations.isEmpty(),
                "scenario launcher production code must stay an external SDK adopter: " + violations);
    }

    @Test
    void productionCodeDoesNotCallTaskCommandRoutes() throws IOException {
        List<Path> violations = mainJavaFiles().stream()
                .filter(path -> contains(path, "/api/v1/tasks/") && contains(path, "/commands"))
                .toList();

        assertTrue(violations.isEmpty(),
                "task launcher must not issue operator task command routes: " + violations);
    }

    private static List<Path> mainJavaFiles() throws IOException {
        Path root = mainJavaRoot();
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }
    }

    private static Path mainJavaRoot() {
        Path moduleRoot = Path.of("src/main/java");
        if (Files.isDirectory(moduleRoot)) {
            return moduleRoot;
        }
        return Path.of("integrations/xa-mass-scenario-launcher/src/main/java");
    }

    private static List<String> forbiddenImportViolations(Path path) {
        try {
            return Files.readAllLines(path).stream()
                    .filter(line -> FORBIDDEN_IMPORT_PREFIXES.stream().anyMatch(line::startsWith))
                    .map(line -> path + " contains " + line.trim())
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + path, e);
        }
    }

    private static boolean contains(Path path, String pattern) {
        try {
            return Files.readString(path).contains(pattern);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + path, e);
        }
    }
}
