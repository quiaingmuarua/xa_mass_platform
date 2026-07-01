package com.xa.mass.testing.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TestingTaskRuntimeOldPathClosureGuardTest {

    @Test
    void manualTestingRunnersDoNotReintroduceOldTaskRuntimeOwnerApis() throws IOException {
        Path sourceRoot = repoPath("xa-mass-testing/src/main/java");
        List<String> forbiddenTokens = List.of(
                "com.xa.mass.runtime.api.TaskWorkRuntime",
                "com.xa.mass.runtime.api.TaskResultRuntime",
                "com.xa.mass.runtime.api.TaskWorkEnvelope",
                "com.xa.mass.runtime.api.TaskWorkResult",
                "com.xa.mass.runtime.api.TaskResultRuntimeRow",
                "com.xa.mass.runtime.api.TaskResultWindow",
                "com.xa.mass.runtime.api.ActiveLeaseRecord",
                "com.xa.mass.runtime.api.ClaimedTaskWork",
                "com.xa.mass.runtime.api.WorkerClaimTarget",
                "com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime",
                "com.xa.mass.runtime.memory.InMemoryTaskResultRuntime",
                "com.xa.mass.runtime.redis.RedisTaskWorkRuntime",
                "com.xa.mass.runtime.redis.RedisTaskResultRuntime",
                "new InMemoryTaskWorkRuntime",
                "new InMemoryTaskResultRuntime",
                "new RedisTaskWorkRuntime",
                "new RedisTaskResultRuntime",
                "getTaskWorkRuntime(",
                "getTaskResultRuntime(",
                "TaskWorkRuntimeStats"
        );

        List<String> violations;
        try (var paths = Files.walk(sourceRoot)) {
            violations = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> forbiddenTokens.stream()
                            .filter(token -> contains(path, token))
                            .map(token -> sourceRoot.relativize(path) + " contains " + token))
                    .toList();
        }

        assertTrue(violations.isEmpty(),
                "xa-mass-testing manual runners must use SDK/task-runtime starter surfaces, not old task runtime owner APIs: "
                        + violations);
    }

    private static boolean contains(Path path, String token) {
        try {
            return Files.readString(path).contains(token);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }

    private static Path repoPath(String path) {
        Path direct = Path.of(path);
        if (Files.exists(direct)) {
            return direct;
        }
        return Path.of("..").resolve(path);
    }
}
