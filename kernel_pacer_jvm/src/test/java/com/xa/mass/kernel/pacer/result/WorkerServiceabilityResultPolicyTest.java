package com.xa.mass.kernel.pacer.result;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.xa.mass.kernel.worker.WorkerServiceabilityEvents;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryReport;
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
    void publishesTheThreeFixedLatestEvidenceEvents() {
        RecordingEvents events = new RecordingEvents();
        WorkerServiceabilityResultPolicy policy = policy(events);

        policy.handle(List.of(
                connection("connected", "CONNECTED", 49_001),
                connection("route", "DISCONNECTED", 49_002),
                expired("expired", 49_003),
                snapshot(49_004, linkedStates(
                        "probe", "UNKNOWN",
                        "connected-snapshot", "CONNECTED"
                ))
        ));

        assertEquals(List.of(
                "connected:{connected=49001, connected-snapshot=49004}:10000",
                "route:{route=49002, expired=49003}",
                "probe:{probe=49004}:5"
        ), events.calls);
    }

    @Test
    void sameTimestampUsesLaterReportAndInvalidEvidenceIsDiscarded() {
        RecordingEvents events = new RecordingEvents();
        WorkerServiceabilityResultPolicy policy = policy(events);

        policy.handle(List.of(
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
                connection("future", "CONNECTED", NOW + 1),
                connection("expired", "CONNECTED", 19_999)
        ));

        assertEquals(List.of("route:{worker-1=49000}"), events.calls);
    }

    @Test
    void malformedSnapshotDoesNotReachWorkerOwner() {
        RecordingEvents events = new RecordingEvents();
        WorkerServiceabilityResultPolicy policy = policy(events);

        policy.handle(List.of(report(
                "platform.adapter.worker-connections.snapshot",
                "{\"stateByWorkerId\":{\"worker-1\":\"INVALID\"}}",
                "worker-serviceability:v1:49000"
        )));

        assertEquals(List.of(), events.calls);
    }

    private static WorkerServiceabilityResultPolicy policy(
            WorkerServiceabilityEvents events
    ) {
        return new WorkerServiceabilityResultPolicy(
                events,
                WorkerServiceabilityResultConfig.defaults(),
                FLOOR,
                () -> NOW,
                JsonMapper.builder().build()
        );
    }

    private static Map<String, String> linkedStates(String... values) {
        LinkedHashMap<String, String> states = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            states.put(values[index], values[index + 1]);
        }
        return states;
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

    private static final class RecordingEvents
            implements WorkerServiceabilityEvents {

        private final List<String> calls = new ArrayList<>();

        @Override
        public void onConnected(
                Map<String, Long> observedAtByWorkerId,
                long hotEligibilityFloorMillis
        ) {
            calls.add("connected:" + observedAtByWorkerId + ":"
                    + hotEligibilityFloorMillis);
        }

        @Override
        public void onRouteUnavailable(
                Map<String, Long> observedAtByWorkerId
        ) {
            calls.add("route:" + observedAtByWorkerId);
        }

        @Override
        public void onProbeUnavailable(
                Map<String, Long> observedAtByWorkerId,
                int maxRecoveryAttempts
        ) {
            calls.add("probe:" + observedAtByWorkerId + ":"
                    + maxRecoveryAttempts);
        }
    }
}
