package com.xa.mass.task.runtime.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskRuntimeStarterArchitectureGuardTest {

    private static final List<String> FORBIDDEN_SNIPPETS = List.of(
            "com.xa.mass.engine",
            "com.xa.mass.transport",
            "com.xa.mass.runtime."
    );

    @Test
    void starterDoesNotImportEngineTransportOrOldRuntimeOwner() throws IOException {
        var violations = new ArrayList<String>();
        try (var files = Files.walk(Path.of("src", "main", "java"))) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> collectViolations(path, violations));
        }

        assertThat(violations).isEmpty();
    }

    private static void collectViolations(Path path, List<String> violations) {
        try {
            var source = Files.readString(path);
            for (var forbidden : FORBIDDEN_SNIPPETS) {
                if (source.contains(forbidden)) {
                    violations.add(path + " contains " + forbidden);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }
}
