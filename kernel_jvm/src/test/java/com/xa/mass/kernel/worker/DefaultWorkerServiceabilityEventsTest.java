package com.xa.mass.kernel.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultWorkerServiceabilityEventsTest {

    @Test
    void mapsTheThreeEventsToTargetPolarityWithoutScoreInterpretation() {
        List<String> calls = new ArrayList<>();
        DefaultWorkerServiceabilityEvents events =
                new DefaultWorkerServiceabilityEvents(
                        recordingScore(calls, null), 40_000L
                );

        events.onAvailable("group-1", Map.of("connected", 49_001L));
        events.onRouteUnavailable("group-1", Map.of("route", 49_002L));
        events.onProbeUnavailable("group-2", Map.of("probe", 49_003L));

        assertEquals(List.of(
                "group-1/HOT_ACQUIRE:{connected=49001}",
                "group-1/RECOVERY_RECHECK:{route=49002}",
                "group-2/RECOVERY_RECHECK:{probe=49003}"
        ), calls);
    }

    @Test
    void scoreEvidenceIsChunkedAtTheOwnerBudget() {
        LinkedHashMap<String, Long> evidence = new LinkedHashMap<>();
        for (int index = 0; index < 201; index++) {
            String workerId = "worker-" + index;
            evidence.put(workerId, 49_000L + index);
        }
        List<Integer> scoreChunks = new ArrayList<>();
        DefaultWorkerServiceabilityEvents events =
                new DefaultWorkerServiceabilityEvents(
                        recordingScore(new ArrayList<>(), scoreChunks), 40_000L
                );

        events.onAvailable("g", evidence);

        assertEquals(List.of(100, 100, 1), scoreChunks);
    }

    @Test
    void invalidEvidenceDoesNotReachScores() {
        List<String> calls = new ArrayList<>();
        var events = new DefaultWorkerServiceabilityEvents(
                recordingScore(calls, null), 40_000L);
        var evidence = new LinkedHashMap<String, Long>();
        evidence.put("valid", 49_000L); evidence.put("invalid", 0L);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> events.onAvailable("g", evidence));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> events.onAvailable(" ", Map.of()));
        events.onAvailable("g", Map.of());
        assertEquals(List.of(), calls);
    }

    @SuppressWarnings("unchecked")
    private static WorkerScoreCore recordingScore(
            List<String> calls,
            List<Integer> chunkSizes
    ) {
        return (WorkerScoreCore) Proxy.newProxyInstance(
                WorkerScoreCore.class.getClassLoader(),
                new Class<?>[]{WorkerScoreCore.class},
                (_proxy, method, args) -> {
                    if (!method.getName().equals(
                            "rewriteCurrentPolarityWithinTimeFence"
                    )) {
                        throw new AssertionError(
                                "Unexpected score call: " + method.getName()
                        );
                    }
                    Map<String, Long> evidence =
                            (Map<String, Long>) args[1];
                    WorkerScorePolarity target =
                            (WorkerScorePolarity) args[2];
                    assertEquals(target == WorkerScorePolarity.HOT_ACQUIRE ? 40_000L : 0L, args[3]);
                    if (chunkSizes != null) {
                        chunkSizes.add(evidence.size());
                    }
                    calls.add(args[0] + "/" + target + ":" + evidence);
                    LinkedHashMap<String, WorkerScoreTransitionResult>
                            results = new LinkedHashMap<>();
                    evidence.keySet().forEach(workerId -> results.put(
                            workerId,
                            new WorkerScoreTransitionResult(
                                    WorkerScoreTransitionStatus.TRANSITIONED,
                                    1L
                            )
                    ));
                    return results;
                }
        );
    }
}
