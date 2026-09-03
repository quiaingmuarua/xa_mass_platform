package com.xa.mass.kernel.pacer.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DispatchMechanismBoundaryTest {

    private static final Path ROOT = Path.of(
            "src/main/java/com/xa/mass/kernel/pacer/dispatch"
    );

    @Test
    void exactHeldScoreReachesFinalWorkerRenewal() throws IOException {
        String source = Files.readString(
                ROOT.resolve("TaskAssignmentDispatcher.java")
        );
        assertTrue(source.contains("worker.heldWorkerLeaseScore()"));
        assertTrue(source.contains("renewActiveHotScoreLeases("));
        for (String parallelInput : List.of(
                "itemsByMessageId",
                "observedItemScores",
                "workersByMessageId"
        )) {
            assertFalse(source.contains(parallelInput));
        }
    }

    @Test
    void onlyMainSchedulerOwnsTheRootTaskSource() throws IOException {
        List<String> callers;
        try (var files = Files.walk(ROOT)) {
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
        Path file = ROOT.resolve("DispatchMainScheduler.java");
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
    void passThroughFacadesAndOpaqueWrappersStayDeleted() {
        for (String type : List.of(
                "WorkerCandidateMechanism",
                "DefaultWorkerCandidateMechanism",
                "TaskExecutionMechanism",
                "DefaultTaskExecutionMechanism",
                "WorkerServiceabilityDispatchMechanism",
                "DefaultWorkerServiceabilityDispatchMechanism",
                "WorkerCandidateReference",
                "WorkerCandidateAcquisitionStrategy",
                "TaskSchedulingReference",
                "TaskItemReference",
                "WorkerSweepCursor",
                "TaskWorkerAllocationConfig",
                "TaskDispatchConfig",
                "TaskInitializationCheck",
                "DueActiveItemInitializationCheck",
                "WorkerServiceabilityDispatchAssemblyConfig",
                "DispatchConvergenceApplication",
                "DispatchDependencies",
                "DispatchContext",
                "AllocationRequest",
                "SelectionResult",
                "AssignmentAttempt"
        )) {
            assertFalse(
                    Files.exists(ROOT.resolve(type + ".java")),
                    () -> type + " must remain deleted"
            );
        }
    }

    @Test
    void matchingImplementationStaysOutsidePacer() throws IOException {
        assertFalse(Files.exists(ROOT.resolve("WorkerCandidateMatcher.java")));
        assertFalse(Files.exists(ROOT.resolve("ConstraintEvaluator.java")));
        try (var files = Files.walk(ROOT)) {
            for (Path file : files.filter(path -> path.toString()
                    .endsWith(".java")).toList()) {
                String source = Files.readString(file);
                for (String forbidden : List.of(
                        "workerProperties",
                        "platformProperties",
                        "allocationRule()",
                        "ConstraintEvaluator",
                        "WorkerCandidateMatcher"
                )) {
                    assertFalse(
                            source.contains(forbidden),
                            () -> file + " must not contain " + forbidden
                    );
                }
            }
        }
    }

    @Test
    void selectionDoesNotInterpretConstraintSyntax() throws IOException {
        String selection = Files.readString(
                ROOT.resolve("WorkerCandidateSelectionPolicy.java")
        );
        for (String forbidden : List.of(
                "workerIdCandidates(",
                "ConstraintEvaluator",
                "ConstraintEvaluator.Operator",
                "\"workerId\"",
                "\"$eq\"",
                "\"$equal\"",
                "\"$in\"",
                "allocationRule().isEmpty()"
        )) {
            assertFalse(
                    selection.contains(forbidden),
                    () -> "WorkerCandidateSelectionPolicy must not contain "
                            + forbidden
            );
        }
        assertFalse(selection.contains("acquireWorkerCandidates("));
        assertFalse(selection.contains("releaseScoreHolds("));
        assertFalse(selection.contains("releaseCompletedHotScoreHolds("));
    }

    @Test
    void dispatchCodeDoesNotDecodeOrCalculateScoreCoordinates()
            throws IOException {
        for (String token : List.of(
                "SLOT_FACTOR",
                "LANE_RANK_FACTOR",
                "DIRTY_FACTOR",
                "Math.abs(",
                "absoluteScore(",
                "decodeScore(",
                "encodedScore()"
        )) {
            try (var files = Files.walk(ROOT)) {
                for (Path file : files.filter(path -> path.toString()
                        .endsWith(".java")).toList()) {
                    String source = Files.readString(file);
                    assertFalse(
                            source.contains(token),
                            () -> file + " must not contain " + token
                    );
                }
            }
        }
    }

    @Test
    void realDispatchClosuresAndRuntimeValuesRemainPackagePrivate()
            throws IOException {
        for (String type : List.of(
                "TaskInitializationPolicy",
                "TaskAssignmentDispatcher",
                "TaskIdleSettlement",
                "DispatchMainScheduler",
                "DispatchProducerId",
                "HeldWorkerCandidate",
                "ObservedTask"
        )) {
            Path file = ROOT.resolve(type + ".java");
            String source = Files.readString(file);
            for (String declaration : List.of(
                    "public interface " + type,
                    "public final class " + type,
                    "public class " + type,
                    "public record " + type
            )) {
                assertFalse(
                        source.contains(declaration),
                        () -> file + " must remain package-private"
                );
            }
        }
    }
}
