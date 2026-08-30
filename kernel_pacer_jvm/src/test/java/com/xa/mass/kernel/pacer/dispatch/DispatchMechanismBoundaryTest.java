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
    void acquiredCandidateIsTheOnlyFlatTerminalCandidateRecord() {
        assertEquals(
                List.of(
                        "workerId",
                        "workerGroupId",
                        "endpointManagerId",
                        "workerLeaseScore"
                ),
                java.util.Arrays.stream(
                        AcquiredWorkerCandidate.class.getRecordComponents()
                ).map(component -> component.getName()).toList()
        );
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
                "TaskSchedulingReference",
                "TaskItemReference",
                "WorkerSweepCursor"
        )) {
            assertFalse(
                    Files.exists(ROOT.resolve(type + ".java")),
                    () -> type + " must remain deleted"
            );
        }
    }

    @Test
    void matcherOwnsOnlyCanonicalRuleMatching()
            throws IOException {
        String matcher = Files.readString(
                ROOT.resolve("WorkerCandidateMatcher.java")
        );
        for (String required : List.of(
                "matchSharedWorkerPool(",
                "matchCandidateScopedWorkerIds(",
                "Map<String, Map<String, Object>> rulesByCandidateId",
                "WorkerResourceCatalog"
        )) {
            assertTrue(
                    matcher.contains(required),
                    () -> "WorkerCandidateMatcher must contain " + required
            );
        }
        for (String forbidden : List.of(
                "limitMatches",
                "uniqueMatches",
                "WorkerScoreCore",
                "CandidateWorkerCache",
                "WorkerCandidateRequest",
                "AcquiredWorkerCandidate",
                "filterCandidateWorkerIds",
                "matchLeasedWorkerCandidates",
                "matchExplicitWorkerIds",
                "workerLeaseScore",
                "priority",
                "requestedCount",
                "Lease",
                "encodedScore()"
        )) {
            assertFalse(
                    matcher.contains(forbidden),
                    () -> "WorkerCandidateMatcher must not contain "
                            + forbidden
            );
        }
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
    void onlyRealCrossOwnerClosuresAndInitializationStrategyRemainInternal()
            throws IOException {
        for (String type : List.of(
                "TaskInitializationCheck",
                "DueActiveItemInitializationCheck",
                "TaskAssignmentDispatcher",
                "TaskIdleSettlement",
                "DispatchMainScheduler",
                "DispatchProducerId",
                "AcquiredWorkerCandidate"
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
