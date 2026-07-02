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

    @Test
    void portSetExposesGroupedRuntimeSurfaceNotOldPortFamilies() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/com/xa/mass/task/runtime/starter/TaskRuntimePortSet.java"));
        String handle = Files.readString(
                Path.of("src/main/java/com/xa/mass/task/runtime/starter/TaskRuntimeHandle.java"));

        assertThat(source)
                .contains("TaskRuntimeWorkPort")
                .contains("TaskRuntimeScorePort")
                .contains("TaskRuntimeConvergencePort")
                .contains("TaskRuntimeReadPort")
                .doesNotContain("TaskRuntimeResultWindowReadModel")
                .doesNotContain("TaskRuntimeResultWindowReadPort")
                .doesNotContain("TaskRuntimeResultPort")
                .doesNotContain("TaskRuntimeAppendPort")
                .doesNotContain("TaskRuntimeSchedulerPort")
                .doesNotContain("TaskRuntimeClaimPort")
                .doesNotContain("TaskRuntimeRepairPort")
                .doesNotContain("TaskRuntimeProgressPort")
                .doesNotContain("TaskRuntimeDiscardPort");
        assertThat(handle)
                .doesNotContain("TaskRuntimeResultWindowReadModel")
                .doesNotContain("resultWindowReadModel()");
    }

    @Test
    void concretePortSetsDoNotExposeOldCompatibilityMethods() throws IOException {
        List<String> forbiddenTokens = List.of(
                "appendBatch(",
                "discoverEligibleTasks(",
                "markTaskDirty(",
                "claimReady(",
                "applyResult(ResultApplyCommand",
                "getResultCorrelation(",
                "pollExpiredActiveLeases(",
                "getActiveWorkForWorker(",
                "discardTaskRuntime(",
                "discardTaskWork(",
                "TaskRuntimeAppendPort",
                "TaskRuntimeSchedulerPort",
                "TaskRuntimeClaimPort",
                "TaskRuntimeResultPort",
                "TaskRuntimeRepairPort",
                "TaskRuntimeProgressPort",
                "TaskRuntimeDiscardPort",
                "TaskRuntimeResultWindowReadPort");
        List<Path> portSets = List.of(
                Path.of("src/main/java/com/xa/mass/task/runtime/starter/MemoryPortSet.java"),
                Path.of("src/main/java/com/xa/mass/task/runtime/starter/RedisPortSet.java"));
        var violations = new ArrayList<String>();
        for (Path portSet : portSets) {
            String source = Files.readString(portSet);
            forbiddenTokens.stream()
                    .filter(source::contains)
                    .forEach(token -> violations.add(portSet + " contains " + token));
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
