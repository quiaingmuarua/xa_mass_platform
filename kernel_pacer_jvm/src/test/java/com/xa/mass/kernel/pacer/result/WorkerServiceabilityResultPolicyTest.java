package com.xa.mass.kernel.pacer.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreState;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryReport;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WorkerServiceabilityResultPolicyTest {

    private static final long NOW = 50_000;
    private static final long FLOOR = 10_000;

    @Test
    void appliesTheFixedEvidencePoliciesThroughScoreOwnerOperations() {
        List<DeliveryReport> reports = List.of(
                connection("connected-recovery", "CONNECTED", 49_001),
                connection("route-hot", "DISCONNECTED", 49_002),
                expired("expired-hot", 49_003),
                snapshot(49_004, Map.of(
                        "probe-hot", "UNKNOWN",
                        "probe-recovery", "DISCONNECTED",
                        "probe-exhaust", "UNKNOWN",
                        "connected-hot", "CONNECTED",
                        "paused", "UNKNOWN"
                ))
        );
        Map<String, WorkerScoreState> states = new LinkedHashMap<>();
        states.put("connected-recovery", state(
                "connected-recovery", -101, WorkerScorePolarity.RECOVERY_RECHECK,
                1_000, 0
        ));
        states.put("route-hot", state(
                "route-hot", 201, WorkerScorePolarity.HOT_ACQUIRE,
                2_000, 0
        ));
        states.put("expired-hot", state(
                "expired-hot", 301, WorkerScorePolarity.HOT_ACQUIRE,
                3_000, 0
        ));
        states.put("probe-hot", state(
                "probe-hot", 401, WorkerScorePolarity.HOT_ACQUIRE,
                4_000, 0
        ));
        states.put("probe-recovery", state(
                "probe-recovery", -503, WorkerScorePolarity.RECOVERY_RECHECK,
                5_000, 1
        ));
        states.put("probe-exhaust", state(
                "probe-exhaust", -609, WorkerScorePolarity.RECOVERY_RECHECK,
                6_000, 4
        ));
        states.put("connected-hot", state(
                "connected-hot", 701, WorkerScorePolarity.HOT_ACQUIRE,
                7_000, 0
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
        WorkerServiceabilityResultPolicy policy = policy(
                catalogFor(states.keySet().stream().toList(), null),
                recordingScore(states, calls)
        );

        policy.handle(reports);

        assertTrue(calls.contains("toggle:connected-recovery:-101"));
        assertTrue(calls.contains("rewrite:connected-recovery:10000:0"));
        assertTrue(calls.contains("toggle:route-hot:201"));
        assertTrue(calls.contains("toggle:expired-hot:301"));
        assertTrue(calls.contains("toggle:probe-hot:401"));
        assertTrue(calls.contains("rewrite:probe-hot:49004:0"));
        assertTrue(calls.contains("rewrite:probe-recovery:49004:2"));
        assertTrue(calls.contains("exhaust:probe-exhaust:-609:5"));
        assertTrue(calls.contains("rewrite:connected-hot:10000:0"));
        assertTrue(calls.stream().noneMatch(call -> call.contains("paused")));
    }

    @Test
    void sameTimestampUsesTheLaterReportAndInvalidEvidenceIsDiscarded() {
        List<String> calls = new ArrayList<>();
        Map<String, WorkerScoreState> states = Map.of(
                "worker-1",
                state(
                        "worker-1",
                        -101,
                        WorkerScorePolarity.RECOVERY_RECHECK,
                        1_000,
                        0
                )
        );
        List<DeliveryReport> reports = List.of(
                connection("worker-1", "CONNECTED", 49_000),
                DeliveryReport.create(
                        DeliveryEndpoint.ADAPTER,
                        "adapter-1",
                        DeliveryEndpoint.KERNEL,
                        "unknown.event",
                        "200",
                        "{}",
                        "worker-serviceability-evidence:v1"
                ),
                connection("worker-1", "DISCONNECTED", 49_000),
                connection("future", "CONNECTED", NOW + 1)
        );
        WorkerServiceabilityResultPolicy policy = policy(
                catalogFor(List.of("worker-1"), null),
                recordingScore(states, calls)
        );

        policy.handle(reports);
        assertTrue(calls.isEmpty());
    }

    @Test
    void workerGroupLookupIsChunkedAtOneHundredWorkers() {
        LinkedHashMap<String, String> states = new LinkedHashMap<>();
        for (int index = 0; index < 100; index++) {
            states.put("worker-" + index, "CONNECTED");
        }
        List<Integer> chunkSizes = new ArrayList<>();
        WorkerServiceabilityResultPolicy policy = policy(
                catalogFor(List.of(), chunkSizes),
                recordingScore(Map.of(), new ArrayList<>())
        );

        policy.handle(List.of(
                snapshot(49_000, states),
                connection("worker-100", "CONNECTED", 49_001)
        ));
        assertEquals(List.of(100, 1), chunkSizes);
    }

    private static WorkerServiceabilityResultPolicy policy(
            WorkerResourceCatalog catalog,
            WorkerScoreCore score
    ) {
        return new WorkerServiceabilityResultPolicy(
                catalog,
                score,
                WorkerServiceabilityResultConfig.defaults(),
                FLOOR,
                () -> NOW,
                JsonMapper.builder().build()
        );
    }

    @SuppressWarnings("unchecked")
    private static WorkerResourceCatalog catalogFor(
            List<String> knownWorkers,
            List<Integer> chunkSizes
    ) {
        return (WorkerResourceCatalog) Proxy.newProxyInstance(
                WorkerResourceCatalog.class.getClassLoader(),
                new Class<?>[]{WorkerResourceCatalog.class},
                (_proxy, method, args) -> {
                    if (!method.getName().equals("getWorkerGroupIds")) {
                        throw new AssertionError(
                                "Unexpected catalog call: " + method.getName()
                        );
                    }
                    List<String> workerIds = (List<String>) args[0];
                    if (chunkSizes != null) {
                        chunkSizes.add(workerIds.size());
                    }
                    LinkedHashMap<String, String> result = new LinkedHashMap<>();
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
            List<String> calls
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
                        yield transitioned();
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

    private static DeliveryReport connection(
            String workerId,
            String state,
            long observedAtMillis
    ) {
        return report(
                "platform.adapter.worker-connection.changed",
                "{\"workerId\":\"" + workerId + "\",\"state\":\""
                        + state + "\",\"observedAtMillis\":"
                        + observedAtMillis + "}",
                "worker-serviceability-evidence:v1"
        );
    }

    private static DeliveryReport expired(
            String workerId,
            long observedAtMillis
    ) {
        return report(
                "platform.adapter.worker-delivery.expired",
                "{\"workerId\":\"" + workerId
                        + "\",\"observedAtMillis\":"
                        + observedAtMillis + "}",
                "worker-serviceability-evidence:v1"
        );
    }

    private static DeliveryReport snapshot(
            long observedAtMillis,
            Map<String, String> states
    ) {
        StringBuilder payload = new StringBuilder("{\"stateByWorkerId\":{");
        boolean first = true;
        for (Map.Entry<String, String> entry : states.entrySet()) {
            if (!first) {
                payload.append(',');
            }
            first = false;
            payload.append('\"').append(entry.getKey()).append("\":\"")
                    .append(entry.getValue()).append('\"');
        }
        payload.append("}}");
        return report(
                "platform.adapter.worker-connections.snapshot",
                payload.toString(),
                "worker-serviceability:v1:" + observedAtMillis
        );
    }

    private static DeliveryReport report(
            String event,
            String payload,
            String forward
    ) {
        return DeliveryReport.create(
                DeliveryEndpoint.ADAPTER,
                "adapter-1",
                DeliveryEndpoint.KERNEL,
                event,
                "200",
                payload,
                forward
        );
    }
}
