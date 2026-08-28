package com.xa.mass.kernel.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreState;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultWorkerServiceabilityEventsTest {

    private static final long FLOOR = 10_000;

    @Test
    void appliesTheFixedWorkerEventMechanisms() {
        Map<String, WorkerScoreState> states = new LinkedHashMap<>();
        states.put("connected-recovery", state(
                "connected-recovery", -101,
                WorkerScorePolarity.RECOVERY_RECHECK, 1_000, 0
        ));
        states.put("connected-hot", state(
                "connected-hot", 201,
                WorkerScorePolarity.HOT_ACQUIRE, 2_000, 0
        ));
        states.put("route-hot", state(
                "route-hot", 301,
                WorkerScorePolarity.HOT_ACQUIRE, 3_000, 0
        ));
        states.put("probe-hot", state(
                "probe-hot", 401,
                WorkerScorePolarity.HOT_ACQUIRE, 4_000, 0
        ));
        states.put("probe-recovery", state(
                "probe-recovery", -503,
                WorkerScorePolarity.RECOVERY_RECHECK, 5_000, 1
        ));
        states.put("probe-exhaust", state(
                "probe-exhaust", -609,
                WorkerScorePolarity.RECOVERY_RECHECK, 6_000, 4
        ));
        states.put("paused", new WorkerScoreState(
                "paused",
                9_999,
                WorkerScorePolarity.HOT_ACQUIRE,
                WorkerScoreCore.PAUSE_TIME_MILLIS,
                0,
                0
        ));
        List<String> calls = new ArrayList<>();
        DefaultWorkerServiceabilityEvents events =
                new DefaultWorkerServiceabilityEvents(
                        catalog(states.keySet().stream().toList(), null),
                        recordingScore(states, calls, true)
                );

        events.onConnected(Map.of(
                "connected-recovery", 49_001L,
                "connected-hot", 49_002L
        ), FLOOR);
        events.onRouteUnavailable(Map.of(
                "route-hot", 49_003L,
                "paused", 49_004L
        ));
        events.onProbeUnavailable(Map.of(
                "probe-hot", 49_005L,
                "probe-recovery", 49_006L,
                "probe-exhaust", 49_007L
        ), 5);

        assertTrue(calls.contains("toggle:connected-recovery:-101"));
        assertTrue(calls.contains("rewrite:connected-recovery:10000:0"));
        assertTrue(calls.contains("rewrite:connected-hot:10000:0"));
        assertTrue(calls.contains("toggle:route-hot:301"));
        assertTrue(calls.contains("toggle:probe-hot:401"));
        assertTrue(calls.contains("rewrite:probe-hot:49005:0"));
        assertTrue(calls.contains("rewrite:probe-recovery:49006:2"));
        assertTrue(calls.contains("exhaust:probe-exhaust:-609:5"));
        assertTrue(calls.stream().noneMatch(call -> call.contains("paused")));
    }

    @Test
    void groupLookupIsChunkedAndStaleToggleStopsFollowUpRewrite() {
        LinkedHashMap<String, Long> evidence = new LinkedHashMap<>();
        List<String> workers = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            String workerId = "worker-" + index;
            workers.add(workerId);
            evidence.put(workerId, 49_000L + index);
        }
        Map<String, WorkerScoreState> states = Map.of(
                "worker-0",
                state(
                        "worker-0", -101,
                        WorkerScorePolarity.RECOVERY_RECHECK, 1_000, 0
                )
        );
        List<Integer> chunkSizes = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        DefaultWorkerServiceabilityEvents events =
                new DefaultWorkerServiceabilityEvents(
                        catalog(workers, chunkSizes),
                        recordingScore(states, calls, false)
                );

        events.onConnected(evidence, FLOOR);

        assertEquals(List.of(100, 1), chunkSizes);
        assertEquals(List.of("toggle:worker-0:-101"), calls);
    }

    @SuppressWarnings("unchecked")
    private static WorkerResourceCatalog catalog(
            List<String> knownWorkers,
            List<Integer> chunkSizes
    ) {
        return (WorkerResourceCatalog) Proxy.newProxyInstance(
                WorkerResourceCatalog.class.getClassLoader(),
                new Class<?>[]{WorkerResourceCatalog.class},
                (_proxy, method, args) -> {
                    if (!method.getName().equals("getWorkerGroupIds")) {
                        throw new AssertionError(
                                "Unexpected catalog call: "
                                        + method.getName()
                        );
                    }
                    List<String> workerIds = (List<String>) args[0];
                    if (chunkSizes != null) {
                        chunkSizes.add(workerIds.size());
                    }
                    LinkedHashMap<String, String> result =
                            new LinkedHashMap<>();
                    for (String workerId : workerIds) {
                        if (knownWorkers.contains(workerId)) {
                            result.put(workerId, "group-1");
                        }
                    }
                    return result;
                }
        );
    }

    @SuppressWarnings("unchecked")
    private static WorkerScoreCore recordingScore(
            Map<String, WorkerScoreState> states,
            List<String> calls,
            boolean transitionToggle
    ) {
        return (WorkerScoreCore) Proxy.newProxyInstance(
                WorkerScoreCore.class.getClassLoader(),
                new Class<?>[]{WorkerScoreCore.class},
                (_proxy, method, args) -> switch (method.getName()) {
                    case "getScoreStates" -> {
                        List<String> workerIds = (List<String>) args[1];
                        LinkedHashMap<String, WorkerScoreState> selected =
                                new LinkedHashMap<>();
                        for (String workerId : workerIds) {
                            if (states.containsKey(workerId)) {
                                selected.put(workerId, states.get(workerId));
                            }
                        }
                        yield selected;
                    }
                    case "toggleCurrentPolarity" -> {
                        calls.add("toggle:" + args[1] + ":" + args[2]);
                        yield transitionToggle
                                ? transitioned()
                                : stale();
                    }
                    case "rewriteCurrentScores" -> {
                        List<String> workerIds = (List<String>) args[1];
                        calls.add("rewrite:" + workerIds.getFirst()
                                + ":" + args[2] + ":" + args[3]);
                        yield Map.of(workerIds.getFirst(), transitioned());
                    }
                    case "exhaustRecoveryRecheck" -> {
                        calls.add("exhaust:" + args[1] + ":" + args[2]
                                + ":" + args[3]);
                        yield transitioned();
                    }
                    default -> throw new AssertionError(
                            "Unexpected score call: " + method.getName()
                    );
                }
        );
    }

    private static WorkerScoreTransitionResult transitioned() {
        return new WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.TRANSITIONED,
                1L
        );
    }

    private static WorkerScoreTransitionResult stale() {
        return new WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.STALE,
                null
        );
    }

    private static WorkerScoreState state(
            String workerId,
            long score,
            WorkerScorePolarity polarity,
            long timeMillis,
            int laneRank
    ) {
        return new WorkerScoreState(
                workerId,
                score,
                polarity,
                timeMillis,
                laneRank,
                0
        );
    }
}
