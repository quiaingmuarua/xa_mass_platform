package com.xa.mass.kernel.pacer.dispatch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DispatchMechanismBoundaryTest {

    @Test
    void onlyMainSchedulerOwnsTheRootTaskSource() throws IOException {
        Path root = Path.of(
                "src/main/java/com/xa/mass/kernel/pacer/dispatch"
        );
        List<String> callers;
        try (var files = Files.walk(root)) {
            callers = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        try {
                            String source = Files.readString(path);
                            return source.contains(".acquireSchedulingTasks(")
                                    || source.contains(
                                    ".filterInitialTaskScores("
                            );
                        } catch (IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    })
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }
        assertEquals(List.of("DispatchMainScheduler.java"), callers);
    }

    @Test
    void mainSchedulerPlansInputsWithoutOwningResourceOperations()
            throws IOException {
        Path file = Path.of(
                "src/main/java/com/xa/mass/kernel/pacer/dispatch/"
                        + "DispatchMainScheduler.java"
        );
        String source = Files.readString(file);
        for (String token : List.of(
                "CandidateWorkerCache",
                "TaskItemScoreBandCore",
                "WorkerScoreCore",
                "WorkerCommandRuntime",
                "WorkerServiceabilityRuntime",
                "ResultContextCodec"
        )) {
            assertFalse(
                    source.contains(token),
                    () -> file + " must not contain " + token
            );
        }
    }

    @Test
    void removedLaneAndGenericBatchTypesStayAbsent() {
        Path root = Path.of(
                "src/main/java/com/xa/mass/kernel/pacer/dispatch"
        );
        for (String type : List.of(
                "DispatchLaneCoordinator",
                "DispatchLaneDefinition",
                "DispatchLaneId",
                "DispatchBatchPolicy",
                "WorkerServiceabilityDispatchLaneConfig",
                "DispatchHandlerDefinition",
                "TaskInitializationBatch",
                "WorkerAllocationBatch",
                "TaskDispatchBatch",
                "WorkerServiceabilityBatch"
        )) {
            assertFalse(
                    Files.exists(root.resolve(type + ".java")),
                    () -> type + " must remain absent"
            );
        }
    }

    @Test
    void dispatchPoliciesDoNotReadRawScoresOrOwnWireConstruction()
            throws IOException {
        Path root = Path.of(
                "src/main/java/com/xa/mass/kernel/pacer/dispatch"
        );
        List<String> forbidden = List.of(
                "TaskScoreBandCore",
                "TaskItemScoreBandCore",
                "WorkerScoreCore",
                "WorkerCommandRuntime",
                "ResultContextCodec",
                "ResultContext",
                "DeliveryCommand",
                "CandidateWorkerEntry",
                ".score()",
                "encodedScore()",
                "workerLeaseScore()"
        );
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString()
                    .endsWith("Policy.java")).toList()) {
                String source = Files.readString(file);
                for (String token : forbidden) {
                    assertFalse(
                            source.contains(token),
                            () -> file + " must not contain " + token
                    );
                }
            }
        }
    }

    @Test
    void dispatchMechanismsStayInternalToThePacerModule() throws IOException {
        Path oldKernelPackage = Path.of(
                "../kernel_jvm/src/main/java/com/xa/mass/kernel/dispatch"
        );
        if (Files.exists(oldKernelPackage)) {
            try (var files = Files.walk(oldKernelPackage)) {
                assertFalse(files.anyMatch(path -> path.toString()
                        .endsWith(".java")));
            }
        }
        Path root = Path.of(
                "src/main/java/com/xa/mass/kernel/pacer/dispatch"
        );
        List<String> internalTypes = List.of(
                "TaskInitializationCheck",
                "DueActiveItemInitializationCheck",
                "DispatchMainScheduler",
                "DispatchProducerId",
                "WorkerCandidateMechanism",
                "DefaultWorkerCandidateMechanism",
                "TaskExecutionMechanism",
                "DefaultTaskExecutionMechanism",
                "WorkerServiceabilityDispatchMechanism",
                "DefaultWorkerServiceabilityDispatchMechanism",
                "TaskSchedulingReference",
                "TaskItemReference",
                "WorkerCandidateReference",
                "WorkerSweepCursor"
        );
        for (String type : internalTypes) {
            Path file = root.resolve(type + ".java");
            String source = Files.readString(file);
            for (String declaration : List.of(
                    "public interface " + type,
                    "public final class " + type,
                    "public class " + type
            )) {
                assertFalse(
                        source.contains(declaration),
                        () -> file + " must remain package-private"
                );
            }
        }
    }
}
