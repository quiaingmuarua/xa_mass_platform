package com.xa.mass.engine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class EngineSchedulingCoreArchitectureGuardTest {

    private static final List<Path> MAINLINE_TEST_FILES = List.of(
            Path.of("src/test/java/com/xa/mass/engine/TaskKernelLifecycleTest.java"),
            Path.of("src/test/java/com/xa/mass/engine/TaskContractTerminalBehaviorTest.java"),
            Path.of("src/test/java/com/xa/mass/engine/TaskContractSchedulingBehaviorTest.java"),
            Path.of("src/test/java/com/xa/mass/engine/TaskSchedulingContentionTest.java"),
            Path.of("src/test/java/com/xa/mass/engine/TaskWorkerEligibilityTest.java"),
            Path.of("src/test/java/com/xa/mass/engine/TaskWorkerContextContentionTest.java"),
            Path.of("src/test/java/com/xa/mass/engine/TaskRedispatchCompetitionTest.java"),
            Path.of("src/test/java/com/xa/mass/engine/TaskSchedulingGateAndTargetingTest.java"),
            Path.of("src/test/java/com/xa/mass/engine/TaskDelayedAvailabilitySchedulingTest.java"),
            Path.of("src/test/java/com/xa/mass/engine/TaskRuntimeRecoveryPortTest.java"),
            Path.of("src/test/java/com/xa/mass/engine/WorkerManagerTest.java"),
            Path.of("src/test/java/com/xa/mass/engine/listener/TaskResourceReleaseListenerTest.java"),
            Path.of("src/test/java/com/xa/mass/engine/listener/TaskAssignWorkerTest.java"),
            Path.of("src/test/java/com/xa/mass/engine/listener/TaskWorkerAssignListenerTest.java"),
            Path.of("src/test/java/com/xa/mass/engine/strategy/RuleBasedTaskWorkerMatchingStrategyTest.java"),
            Path.of("src/test/java/com/xa/mass/engine/model/WorkerMatchContextTest.java")
    );

    private static final List<String> FORBIDDEN_MAINLINE_TOKENS = List.of(
            "TaskMessageProjection",
            "TaskMessageAttemptProjection",
            "CompatibilityProjection",
            "ProjectionTestViews",
            "getTaskMessage"
    );

    @Test
    void schedulingCoreMainlineTestsDoNotUseCompatibilityProjectionAsProofSurface() throws IOException {
        for (Path path : MAINLINE_TEST_FILES) {
            String source = Files.readString(path);
            for (String forbiddenToken : FORBIDDEN_MAINLINE_TOKENS) {
                assertFalse(
                        source.contains(forbiddenToken),
                        path + " must not use " + forbiddenToken + " in the scheduling-core mainline"
                );
            }
        }
    }
}
