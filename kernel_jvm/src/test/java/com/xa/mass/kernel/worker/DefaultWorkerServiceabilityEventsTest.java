package com.xa.mass.kernel.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.worker.WorkerServiceabilityEvents.NetworkObservation;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerDescriptor;
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

        events.onAvailable(Map.of("connected", new NetworkObservation("adapter-1", 49_001L)));
        events.onRouteUnavailable(Map.of("route", new NetworkObservation("adapter-1", 49_002L)));
        events.onProbeUnavailable(Map.of("probe", new NetworkObservation("adapter-1", 49_003L)));

        assertEquals(List.of(
                "HOT_ACQUIRE:{connected=49001}",
                "RECOVERY_RECHECK:{route=49002}",
                "RECOVERY_RECHECK:{probe=49003}"
        ), calls);
    }

    @Test
    void bindingLookupAndScoreEvidenceAreBothChunkedToOneHundred() {
        LinkedHashMap<String, NetworkObservation> evidence = new LinkedHashMap<>();
        List<String> workers = new ArrayList<>();
        for (int index = 0; index < 201; index++) {
            String workerId = "worker-" + index;
            workers.add(workerId);
            evidence.put(workerId, new NetworkObservation("adapter-1", 49_000L + index));
        }
        List<Integer> lookupChunks = new ArrayList<>();
        List<Integer> scoreChunks = new ArrayList<>();
        DefaultWorkerServiceabilityEvents events =
                new DefaultWorkerServiceabilityEvents(
                        catalog(workers, lookupChunks),
                        recordingScore(new ArrayList<>(), scoreChunks)
                );

        events.onAvailable(evidence);

        assertEquals(List.of(100, 100, 1), lookupChunks);
        assertEquals(List.of(100, 100, 1), scoreChunks);
    }

    @Test
    void missingBindingOrWrongEndpointDoesNotReachScores() {
        List<String> calls = new ArrayList<>();
        var events = new DefaultWorkerServiceabilityEvents(
                catalog(List.of("known"), null), recordingScore(calls, null));
        events.onAvailable(Map.of("known", new NetworkObservation("wrong", 49_000L),
                "missing", new NetworkObservation("adapter-1", 49_000L)));
        events.onRouteUnavailable(Map.of("known", new NetworkObservation("wrong", 49_001L)));
        assertEquals(List.of(), calls);
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
                    if (!method.getName().equals("getWorkerDescriptors")) {
                        throw new AssertionError(
                                "Unexpected catalog call: "
                                        + method.getName()
                        );
                    }
                    List<String> workerIds = (List<String>) args[0];
                    if (chunkSizes != null) {
                        chunkSizes.add(workerIds.size());
                    }
                    LinkedHashMap<String, WorkerDescriptor> result =
                            new LinkedHashMap<>();
                    for (String workerId : workerIds) {
                        if (knownWorkers.contains(workerId)) {
                            result.put(workerId, new WorkerDescriptor(workerId, "group-1", "adapter-1"));
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
