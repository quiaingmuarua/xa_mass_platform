package com.xa.mass.task.runtime.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskRuntimeLoopCutoverGuardTest {

    private static final List<String> OLD_ENGINE_LOOP_TOKENS = List.of(
            "EngineRuntimeKernel",
            "RuntimeReadyDispatchPump",
            "LeaseExpireWatchdog",
            "TaskResultService",
            "SimpleTaskDispatchBinder"
    );

    @Test
    void starterDoesNotSilentlyRegisterOldEngineProductionLoops() throws IOException {
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
            for (var token : OLD_ENGINE_LOOP_TOKENS) {
                if (source.contains(token)) {
                    violations.add(path + " references old engine loop token " + token);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }
}
