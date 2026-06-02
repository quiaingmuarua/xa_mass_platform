package com.xa.mass.workerpack;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerPackArchitectureGuardTest {
    private static final List<String> TOOL_FORBIDDEN_IMPORTS = List.of(
            "import com.xa.mass.workerpack.sample.",
            "import com.xa.mass.sdk.",
            "import com.xa.mass.transport."
    );

    @Test
    void capabilityRuntimeDoesNotDependOnSampleHarnessOrEmbeddedRuntime() throws IOException {
        Path toolRoot = repoRoot().resolve("integrations/xa-mass-worker-pack/src/main/java/com/xa/mass/workerpack/tool");
        List<Path> violations;
        try (var stream = Files.walk(toolRoot)) {
            violations = stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(WorkerPackArchitectureGuardTest::hasForbiddenToolImport)
                    .toList();
        }

        assertTrue(violations.isEmpty(),
                () -> "worker-pack tool capabilities must not import sample harness, embedded runtime, or transport: "
                        + violations);
    }

    private static boolean hasForbiddenToolImport(Path path) {
        try {
            String source = Files.readString(path);
            return TOOL_FORBIDDEN_IMPORTS.stream().anyMatch(source::contains);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + path, e);
        }
    }

    private static Path repoRoot() {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            if (Files.exists(cursor.resolve("pom.xml"))
                    && Files.exists(cursor.resolve("integrations/xa-mass-worker-pack/pom.xml"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("repo root not found");
    }
}
