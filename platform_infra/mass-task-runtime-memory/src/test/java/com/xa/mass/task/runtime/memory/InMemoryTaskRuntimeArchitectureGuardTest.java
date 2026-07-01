package com.xa.mass.task.runtime.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryTaskRuntimeArchitectureGuardTest {

    private static final List<String> FORBIDDEN_SNIPPETS = List.of(
            "com.xa.mass.runtime.",
            "com.xa.mass.engine",
            "com.xa.mass.transport",
            "org.springframework",
            "io.lettuce",
            "redis.clients"
    );

    @Test
    void memoryAdapterDependsOnlyOnTaskRuntimeContractOwner() throws IOException {
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
            for (String forbidden : FORBIDDEN_SNIPPETS) {
                if (source.contains(forbidden)) {
                    violations.add(path + " contains " + forbidden);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }
}
