package com.xa.mass.testing.perf;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskFlowLoadModelRunnerTaskRuntimeGuardTest {

    @Test
    void loadModelRunnerUsesStarterBackedTaskRuntimeInsteadOfLegacyRuntimeBundle() throws Exception {
        String source = Files.readString(sourcePath());

        for (String token : List.of(
                "com.xa.mass.runtime.api.TaskWorkRuntime",
                "com.xa.mass.runtime.api.TaskResultRuntime",
                "com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime",
                "com.xa.mass.runtime.memory.InMemoryTaskResultRuntime",
                "com.xa.mass.runtime.redis.RedisTaskWorkRuntime",
                "com.xa.mass.runtime.redis.RedisTaskResultRuntime",
                "RuntimeBundle",
                "MeasuredTaskWorkRuntime",
                "MeasuredTaskResultRuntime",
                "RuntimeOperationMetrics",
                "runtimeOperations",
                "slowestRuntimeOp",
                "staleResultEveryNth",
                "syntheticStaleResults",
                "syntheticStaleResultRejected",
                "staleResultItems"
        )) {
            assertFalse(source.contains(token),
                    "TaskFlowLoadModelRunner must not reintroduce legacy task runtime token: " + token);
        }

        assertTrue(source.contains("engineConfig.useMemoryTaskRuntime()"),
                "memory backend must start through EngineConfig task-runtime starter");
        assertTrue(source.contains("engineConfig.useRedisTaskRuntime("),
                "Redis backend must start through EngineConfig task-runtime starter");
        assertTrue(source.contains("engineConfig.shutdownTaskRuntime()"),
                "runner must close the starter-owned task runtime handle");
        assertTrue(source.contains("cleanupTaskRuntimeNamespace(config)"),
                "runner must isolate task-runtime Redis namespaces for manual perf proof");
    }

    @Test
    void loadProofDoesNotCarrySyntheticStaleResultHook() throws Exception {
        String runner = Files.readString(sourcePath());
        String script = Files.readString(repoPath("xa-mass-testing/scripts/run-perf-smokes.sh"));
        String readme = Files.readString(repoPath("xa-mass-testing/README.md"));

        for (String token : List.of(
                "mass.load.staleResultEveryNth",
                "MASS_PERF_TASK_FLOW_STALE_RESULT_EVERY_NTH",
                "staleResultItems",
                "syntheticStaleResults",
                "syntheticStaleResultRejected",
                "synthetic stale-result"
        )) {
            assertFalse(runner.contains(token), "load runner must not reintroduce synthetic stale-result hook: " + token);
            assertFalse(script.contains(token), "perf smoke script must not reintroduce synthetic stale-result hook: " + token);
            assertFalse(readme.contains(token), "perf README must not advertise synthetic stale-result hook: " + token);
        }
    }

    private static Path sourcePath() {
        Path source = Path.of("src/main/java/com/xa/mass/testing/perf/TaskFlowLoadModelRunner.java");
        if (Files.isRegularFile(source)) {
            return source;
        }
        return Path.of("xa-mass-testing").resolve(source);
    }

    private static Path repoPath(String path) {
        Path direct = Path.of(path);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        return Path.of("..").resolve(path);
    }
}
