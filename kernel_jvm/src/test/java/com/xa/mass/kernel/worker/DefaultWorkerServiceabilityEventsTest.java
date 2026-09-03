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
                        catalog(
                                List.of("connected", "route", "probe"),
                                null
                        ),
                        recordingScore(calls, null)
                );

        events.onConnected(Map.of("connected", 49_001L));
        events.onRouteUnavailable(Map.of("route", 49_002L));
        events.onProbeUnavailable(Map.of("probe", 49_003L));

        assertEquals(List.of(
                "HOT_ACQUIRE:{connected=49001}",
                "RECOVERY_RECHECK:{route=49002}",
                "RECOVERY_RECHECK:{probe=49003}"
        ), calls);
    }

    @Test
    void groupLookupAndScoreEvidenceAreBothChunkedToOneHundred() {
        LinkedHashMap<String, Long> evidence = new LinkedHashMap<>();
        List<String> workers = new ArrayList<>();
        for (int index = 0; index < 201; index++) {
            String workerId = "worker-" + index;
            workers.add(workerId);
            evidence.put(workerId, 49_000L + index);
        }
        List<Integer> lookupChunks = new ArrayList<>();
        List<Integer> scoreChunks = new ArrayList<>();
        DefaultWorkerServiceabilityEvents events =
                new DefaultWorkerServiceabilityEvents(
                        catalog(workers, lookupChunks),
                        recordingScore(new ArrayList<>(), scoreChunks)
                );

        events.onConnected(evidence);

        assertEquals(List.of(100, 100, 1), lookupChunks);
        assertEquals(List.of(100, 100, 1), scoreChunks);
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
            List<String> calls,
            List<Integer> chunkSizes
    ) {
        return (WorkerScoreCore) Proxy.newProxyInstance(
                WorkerScoreCore.class.getClassLoader(),
                new Class<?>[]{WorkerScoreCore.class},
                (_proxy, method, args) -> {
                    if (!method.getName().equals(
                            "applyServiceabilityEvidence"
                    )) {
                        throw new AssertionError(
                                "Unexpected score call: " + method.getName()
                        );
                    }
                    Map<String, Long> evidence =
                            (Map<String, Long>) args[1];
                    WorkerScorePolarity target =
                            (WorkerScorePolarity) args[2];
                    if (chunkSizes != null) {
                        chunkSizes.add(evidence.size());
                    }
                    calls.add(target + ":" + evidence);
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
