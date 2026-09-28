package com.xa.mass.kernel.pacer.result;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.xa.mass.kernel.worker.WorkerServiceabilityEvents;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerDescriptor;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryReport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WorkerServiceabilityResultPolicyTest {

    private static final long NOW = 50_000;

    @Test
    void pollingHasItsOwnServerSourceAndRejectsForgedOrExpiredObservations() {
        var events = org.mockito.Mockito.mock(WorkerServiceabilityEvents.class);
        var policy = policy(events);
        String type = "platform.server.worker-poll.observed";
        String payload = "{\"workerId\":\"polling\",\"observedAtMillis\":49000}";
        policy.handle(List.of(
                DeliveryReport.create(
                        DeliveryEndpoint.ADAPTER,
                        "system-polling",
                        DeliveryEndpoint.KERNEL,
                        type,
                        "",
                        payload,
                        "worker-serviceability-evidence:v1"
                ),
                DeliveryReport.create(
                        DeliveryEndpoint.SERVER,
                        "adapter-1",
                        DeliveryEndpoint.KERNEL,
                        type,
                        "",
                        payload,
                        "worker-serviceability-evidence:v1"
                ),
                DeliveryReport.create(
                        DeliveryEndpoint.SERVER,
                        "system-polling",
                        DeliveryEndpoint.KERNEL,
                        type,
                        "",
                        payload.replace("49000", "19000"),
                        "worker-serviceability-evidence:v1"
                ),
                DeliveryReport.create(
                        DeliveryEndpoint.SERVER,
                        "system-polling",
                        DeliveryEndpoint.KERNEL,
                        type,
                        "",
                        payload.replace("49000", "50001"),
                        "worker-serviceability-evidence:v1"
                )
        ));
        org.mockito.Mockito.verifyNoInteractions(events);
        policy.handle(List.of(DeliveryReport.create(
                DeliveryEndpoint.SERVER,
                "system-polling",
                DeliveryEndpoint.KERNEL,
                type,
                "",
                payload,
                "worker-serviceability-evidence:v1"
        )));
        org.mockito.Mockito.verify(events).onAvailable("g", Map.of("polling", 49_000L));
    }

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
                "connected:{connected=49001, connected-snapshot=49004}",
                "route:{route=49002, expired=49003}",
                "probe:{probe=49004}"
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
                        "",
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
                "platform.adapter.command.succeeded",
                "{\"stateByWorkerId\":{\"worker-1\":\"INVALID\"}}",
                "worker-serviceability:v1:49000"
        )));

        assertEquals(List.of(), events.calls);
    }

    @Test
    void snapshotRequiresExactSuccessEventAndProbeCorrelationNotSuccessDiagnostic() {
        RecordingEvents events = new RecordingEvents();
        var policy = policy(events);
        var snapshot = snapshot(49_000, Map.of("worker", "CONNECTED"));
        for (String event : List.of("platform.adapter.worker-connections.snapshot",
                "platform.adapter.command.failed", "extension.adapter.probe.succeeded",
                "platform.worker.command.succeeded")) {
            policy.handle(List.of(DeliveryReport.create(DeliveryEndpoint.ADAPTER, "adapter-1",
                    DeliveryEndpoint.KERNEL, event, "200", snapshot.payload(), snapshot.forward())));
        }
        policy.handle(List.of(DeliveryReport.create(DeliveryEndpoint.ADAPTER, "adapter-1",
                DeliveryEndpoint.KERNEL, snapshot.messageType(), "200", snapshot.payload(), "direct-call:v1:test")));
        assertEquals(List.of(), events.calls);
        policy.handle(List.of(DeliveryReport.create(DeliveryEndpoint.ADAPTER, "adapter-1",
                DeliveryEndpoint.KERNEL, snapshot.messageType(), "3303", snapshot.payload(), snapshot.forward())));
        assertEquals(List.of("connected:{worker=49000}"), events.calls);
    }

    private static WorkerServiceabilityResultPolicy policy(
            WorkerServiceabilityEvents events
    ) {
        return policy(events, catalog(), (group, times) -> times.keySet());
    }

    private static WorkerServiceabilityResultPolicy policy(WorkerServiceabilityEvents events,
            WorkerResourceCatalog catalog, BiFunction<String, Map<String, Long>, Set<String>> filter) {
        return new WorkerServiceabilityResultPolicy(
                events,
                catalog,
                filter,
                WorkerServiceabilityResultConfig.defaults(),
                () -> NOW,
                JsonMapper.builder().build()
        );
    }

    @SuppressWarnings("unchecked")
    @Test void invalidSourcesAndBindingsNeverReachTheFilterOrScores() {
        var events = mock(WorkerServiceabilityEvents.class);
        var catalog = mock(WorkerResourceCatalog.class);
        var filter = (BiFunction<String, Map<String, Long>, Set<String>>) mock(BiFunction.class);
        var policy = policy(events, catalog, filter);
        policy.handle(List.of(connection("future", "CONNECTED", NOW + 1),
                connection("too-old", "CONNECTED", 1)));
        verifyNoInteractions(catalog, filter, events);
        when(catalog.getWorkerDescriptors(anyList())).thenReturn(Map.of(
                "wrong", new WorkerDescriptor("wrong", "g", "other-adapter")));
        policy.handle(List.of(connection("wrong", "CONNECTED", 49_000),
                connection("missing", "CONNECTED", 49_000)));
        verifyNoInteractions(filter, events);
    }

    @Test void bindingPagesAreSharedAcrossKindsAndFilteringUsesTheResolvedGroup() {
        var catalog = catalog();
        when(catalog.getWorkerDescriptors(anyList())).thenAnswer(call -> {
            var result = new LinkedHashMap<String, WorkerDescriptor>();
            for (String id : call.<List<String>>getArgument(0))
                result.put(id, new WorkerDescriptor(id, Integer.parseInt(id) % 2 == 0 ? "a" : "b", "adapter-1"));
            return result;
        });
        var filtered = new LinkedHashMap<String, Map<String, Long>>();
        var events = mock(WorkerServiceabilityEvents.class);
        var policy = policy(events, catalog, (group, times) -> {
            assertThrows(UnsupportedOperationException.class, times::clear);
            filtered.put(group, times);
            return group.equals("a") ? times.keySet() : Set.of();
        });
        var reports = new ArrayList<DeliveryReport>();
        for (int i = 0; i < 201; i++) reports.add(connection(Integer.toString(i),
                i % 3 == 0 ? "DISCONNECTED" : "CONNECTED", 49_000));
        policy.handle(reports);
        var pages = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(catalog, times(3)).getWorkerDescriptors(pages.capture());
        assertEquals(List.of(100, 100, 1), pages.getAllValues().stream().map(List::size).toList());
        assertEquals(101, filtered.get("a").size());
        assertEquals(100, filtered.get("b").size());
        verify(events).onAvailable(eq("a"), anyMap());
        verify(events).onRouteUnavailable(eq("a"), anyMap());
        verifyNoMoreInteractions(events);
    }

    @Test void allNetworkKindsShareTheFilterAcrossBatchesAndEqualTimesStillPass() {
        var events = new RecordingEvents();
        var remembered = new LinkedHashMap<String, Long>();
        var policy = policy(events, catalog(), rememberedFilter(remembered));
        policy.handle(List.of(connection("w", "CONNECTED", 49_000)));
        policy.handle(List.of(snapshot(48_999, Map.of("w", "UNKNOWN"))));
        policy.handle(List.of(expired("w", 48_998)));
        policy.handle(List.of(connection("w", "DISCONNECTED", 49_000)));
        policy.handle(List.of(snapshot(49_001, Map.of("w", "CONNECTED"))));
        policy.handle(List.of(expired("w", 49_002)));
        assertEquals(List.of("connected:{w=49000}", "route:{w=49000}",
                "connected:{w=49001}", "route:{w=49002}"), events.calls);
        assertEquals(49_002L, remembered.get("g/w"));
        policy.handle(List.of(report("platform.server.worker-poll.observed", "{}", "")));
        assertEquals(4, events.calls.size());
    }

    @Test void pollingEvidenceUsesTheSamePersistentFilterContract() {
        var remembered = new LinkedHashMap<String, Long>();
        var events = new RecordingEvents();
        var policy = policy(events, catalog(), rememberedFilter(remembered));
        for (long time : List.of(49_000L, 48_999L, 49_000L, 49_001L)) {
            policy.handle(List.of(DeliveryReport.create(DeliveryEndpoint.SERVER, "system-polling",
                    DeliveryEndpoint.KERNEL, "platform.server.worker-poll.observed", "",
                    "{\"workerId\":\"polling\",\"observedAtMillis\":" + time + "}",
                    "worker-serviceability-evidence:v1")));
        }
        assertEquals(List.of("connected:{polling=49000}", "connected:{polling=49000}",
                "connected:{polling=49001}"), events.calls);
        assertEquals(49_001L, remembered.get("g/polling"));
    }

    @Test void filterFailurePreservesOriginalEvidenceWithoutRetry() {
        var events = new RecordingEvents();
        var filterCalls = new ArrayList<Map<String, Long>>();
        var policy = policy(events, catalog(), (group, times) -> {
            filterCalls.add(times);
            throw new IllegalStateException("filter unavailable");
        });
        policy.handle(List.of(connection("w", "CONNECTED", 49_000)));
        policy.handle(List.of(snapshot(49_001, Map.of("w", "UNKNOWN"))));
        assertEquals(List.of("connected:{w=49000}", "probe:{w=49001}"), events.calls);
        assertEquals(2, filterCalls.size());
    }

    @Test void kernelFailureDoesNotRollbackAnObservationOrRetryTheFilter() {
        var remembered = new LinkedHashMap<String, Long>();
        var events = mock(WorkerServiceabilityEvents.class);
        doThrow(new IllegalStateException("Score unavailable")).when(events).onAvailable(anyString(), anyMap());
        var policy = policy(events, catalog(), rememberedFilter(remembered));
        assertThrows(IllegalStateException.class, () -> policy.handle(List.of(connection("w", "CONNECTED", 49_000))));
        policy.handle(List.of(connection("w", "DISCONNECTED", 48_999)));
        assertEquals(49_000L, remembered.get("g/w"));
        verify(events).onAvailable("g", Map.of("w", 49_000L));
        verifyNoMoreInteractions(events);
    }

    private static BiFunction<String, Map<String, Long>, Set<String>> rememberedFilter(Map<String, Long> remembered) {
        return (group, times) -> {
            var accepted = new java.util.LinkedHashSet<String>();
            times.forEach((id, time) -> {
                String key = group + "/" + id;
                if (time >= remembered.getOrDefault(key, 0L)) {
                    remembered.put(key, time);
                    accepted.add(id);
                }
            });
            return accepted;
        };
    }

    private static WorkerResourceCatalog catalog() {
        var catalog = mock(WorkerResourceCatalog.class);
        when(catalog.getWorkerDescriptors(anyList())).thenAnswer(call -> {
            var result = new LinkedHashMap<String, WorkerDescriptor>();
            for (String id : call.<List<String>>getArgument(0))
                result.put(id, new WorkerDescriptor(id, "g", id.equals("polling") ? "system-polling" : "adapter-1"));
            return result;
        });
        return catalog;
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
                "platform.adapter.command.succeeded",
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
                "",
                payload,
                forward
        );
    }

    private static final class RecordingEvents
            implements WorkerServiceabilityEvents {

        private final List<String> calls = new ArrayList<>();

        @Override
        public void onAvailable(String group, Map<String, Long> observedAtByWorkerId) {
            calls.add("connected:" + observedAtByWorkerId);
        }

        @Override
        public void onRouteUnavailable(
                String group, Map<String, Long> observedAtByWorkerId
        ) {
            calls.add("route:" + observedAtByWorkerId);
        }

        @Override
        public void onProbeUnavailable(
                String group, Map<String, Long> observedAtByWorkerId
        ) {
            calls.add("probe:" + observedAtByWorkerId);
        }
    }
}
