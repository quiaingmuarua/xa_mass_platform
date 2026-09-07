package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.KERNEL;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.SYSTEM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionMechanism;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionState;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerPropertiesObservation;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdapterEventDispatcherTest {

    @Test
    void staticallyAssembledHandlerMapDispatchesCustomEvent() {
        Map<String, AdapterEventDispatcher.AdapterEventHandler> handlers =
                new LinkedHashMap<>();
        handlers.put("platform.adapter.custom", payload -> payload);
        AdapterEventDispatcher dispatcher = new AdapterEventDispatcher(
                "adapter-1",
                handlers
        );
        handlers.clear();

        var report = dispatcher.dispatch(command(
                "platform.adapter.custom",
                "{\"value\":1}"
        ));

        assertThat(report.outcomeCode()).isEqualTo("200");
        assertThat(report.payload()).isEqualTo("{\"value\":1}");
    }

    @Test
    void eventSnapshotDescribesTheExactImmutableHandlerMap() {
        Map<String, AdapterEventDispatcher.AdapterEventHandler> handlers =
                new LinkedHashMap<>();
        handlers.put("platform.adapter.zeta", payload -> payload);
        handlers.put("platform.adapter.alpha", payload -> payload);
        AdapterEventDispatcher dispatcher = new AdapterEventDispatcher(
                "adapter-1",
                handlers
        );
        handlers.clear();

        var report = dispatcher.dispatch(command(
                AdapterEventDispatcher.EVENTS_SNAPSHOT_EVENT,
                "null"
        ));

        assertThat(report.outcomeCode()).isEqualTo("200");
        assertThat(Jsons.parseObject(report.payload()).get("eventNames"))
                .isEqualTo(List.of(
                        "platform.adapter.alpha",
                        "platform.adapter.events.snapshot",
                        "platform.adapter.zeta"
                ));
    }

    @Test
    void eventSnapshotIsReservedAndRequiresNullPayload() {
        assertThatThrownBy(() -> new AdapterEventDispatcher(
                "adapter-1",
                Map.of(
                        AdapterEventDispatcher.EVENTS_SNAPSHOT_EVENT,
                        payload -> payload
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");

        AdapterEventDispatcher dispatcher = new AdapterEventDispatcher(
                "adapter-1",
                Map.of()
        );
        assertThat(dispatcher.dispatch(command(
                AdapterEventDispatcher.EVENTS_SNAPSHOT_EVENT,
                "{}"
        )).outcomeCode()).isEqualTo(Integer.toString(
                WorkerDeliveryAdapterErrorCode.ADAPTER_COMMAND_INVALID.code()
        ));
    }

    @Test
    void handlerFailureIsAnObservedExecutionFailure() {
        AdapterEventDispatcher dispatcher = new AdapterEventDispatcher(
                "adapter-1",
                Map.of("platform.adapter.failure", payload -> {
                    throw new Exception("failed");
                })
        );

        var report = dispatcher.dispatch(command(
                "platform.adapter.failure",
                "null"
        ));

        assertThat(report.outcomeCode()).isEqualTo(Integer.toString(
                WorkerDeliveryAdapterErrorCode
                        .ADAPTER_EVENT_EXECUTION_FAILED.code()
        ));
        assertThat(report.payload()).isEqualTo("null");
    }

    @Test
    void handlerIllegalArgumentFailureIsNotMisclassifiedAsInvalidPayload() {
        AdapterEventDispatcher dispatcher = new AdapterEventDispatcher(
                "adapter-1",
                Map.of("platform.adapter.failure", payload -> {
                    throw new IllegalArgumentException("handler bug");
                })
        );

        var report = dispatcher.dispatch(command(
                "platform.adapter.failure",
                "null"
        ));

        assertThat(report.outcomeCode()).isEqualTo(Integer.toString(
                WorkerDeliveryAdapterErrorCode
                        .ADAPTER_EVENT_EXECUTION_FAILED.code()
        ));
    }

    @Test
    void handlerMapRejectsNonPlatformAdapterEventNames() {
        assertThatThrownBy(() -> new AdapterEventDispatcher(
                "adapter-1",
                Map.of("extension.adapter.custom", payload -> payload)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("platform.adapter");
    }

    @Test
    void kernelCanOnlyExecuteTheWorkerConnectionSnapshot() {
        WorkerConnectionMechanism connections = mock(
                WorkerConnectionMechanism.class
        );
        when(connections.connectionStates(List.of("worker-1")))
                .thenReturn(Map.of(
                        "worker-1",
                        WorkerConnectionState.CONNECTED
                ));
        AdapterEventDispatcher dispatcher = AdapterEventDispatcher.defaults(
                "adapter-1",
                connections
        );

        DeliveryCommand snapshot = DeliveryCommand.create(
                KERNEL,
                ADAPTER,
                AdapterEventDispatcher.CONNECTION_SNAPSHOT_EVENT,
                System.currentTimeMillis() + 10_000,
                "{\"workerIds\":[\"worker-1\"]}",
                "worker-serviceability:v1:123"
        );
        var report = dispatcher.dispatch(snapshot);

        assertThat(report.src()).isEqualTo(ADAPTER);
        assertThat(report.dst()).isEqualTo(KERNEL);
        assertThat(report.outcomeCode()).isEqualTo("200");
        assertThat(Jsons.parseObject(report.payload()))
                .containsEntry(
                        "stateByWorkerId",
                        Map.of("worker-1", "CONNECTED")
                );

        DeliveryCommand forbidden = DeliveryCommand.create(
                KERNEL,
                ADAPTER,
                AdapterEventDispatcher.CLOSE_CURRENT_EVENT,
                System.currentTimeMillis() + 10_000,
                "{\"workerIds\":[\"worker-1\"]}",
                "worker-serviceability:v1:123"
        );
        assertThat(dispatcher.dispatch(forbidden).outcomeCode())
                .isEqualTo(Integer.toString(
                        WorkerDeliveryAdapterErrorCode
                                .ADAPTER_COMMAND_INVALID.code()
                ));
    }

    @Test
    void defaultPropertiesEventReturnsOrderedCachedPropertiesOnly() {
        WorkerConnectionMechanism connections = mock(
                WorkerConnectionMechanism.class
        );
        Map<String, WorkerPropertiesObservation> snapshots =
                new LinkedHashMap<>();
        snapshots.put(
                "worker-1",
                new WorkerPropertiesObservation(
                        123L,
                        Map.of("battery", "87")
                )
        );
        snapshots.put(
                "older",
                new WorkerPropertiesObservation(
                        100L,
                        Map.of("battery", "40")
                )
        );
        snapshots.put(
                "unknown",
                new WorkerPropertiesObservation(
                        null,
                        null
                )
        );
        when(connections.workerProperties(List.of(
                "worker-1",
                "older",
                "unknown"
        ))).thenReturn(snapshots);
        AdapterEventDispatcher dispatcher = AdapterEventDispatcher.defaults(
                "adapter-1",
                connections
        );

        var report = dispatcher.dispatch(command(
                AdapterEventDispatcher.WORKER_PROPERTIES_SNAPSHOT_EVENT,
                "{\"workerIds\":[\"worker-1\",\"older\",\"unknown\"]}"
        ));

        assertThat(report.outcomeCode()).isEqualTo("200");
        Map<String, Object> payload = Jsons.parseObject(report.payload());
        assertThat(payload).containsOnlyKeys("propertiesByWorkerId");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) payload.get(
                "propertiesByWorkerId"
        );
        assertThat(properties.keySet()).containsExactly(
                "worker-1",
                "older",
                "unknown"
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> fresh = (Map<String, Object>) properties
                .get("worker-1");
        assertThat(fresh)
                .containsOnlyKeys(
                        "updatedAtMillis",
                        "properties"
                )
                .containsEntry("updatedAtMillis", 123L)
                .containsEntry("properties", Map.of("battery", "87"))
                .doesNotContainKeys(
                        "connectionState",
                        "workerGroupId",
                        "freshness",
                        "version",
                        "observedAtMillis"
                );
        @SuppressWarnings("unchecked")
        Map<String, Object> older = (Map<String, Object>) properties
                .get("older");
        assertThat(older)
                .containsEntry("updatedAtMillis", 100L)
                .containsEntry("properties", Map.of("battery", "40"));
        @SuppressWarnings("unchecked")
        Map<String, Object> unknown = (Map<String, Object>) properties
                .get("unknown");
        assertThat(unknown)
                .containsOnlyKeys(
                        "updatedAtMillis",
                        "properties"
                )
                .containsEntry("updatedAtMillis", null)
                .containsEntry("properties", null);

        var events = dispatcher.dispatch(command(
                AdapterEventDispatcher.EVENTS_SNAPSHOT_EVENT,
                "null"
        ));
        @SuppressWarnings("unchecked")
        List<String> eventNames = (List<String>) Jsons.parseObject(
                events.payload()
        ).get("eventNames");
        assertThat(eventNames)
                .contains(AdapterEventDispatcher.WORKER_PROPERTIES_SNAPSHOT_EVENT)
                .doesNotContain("platform.adapter.worker-observations.snapshot");

        assertThat(dispatcher.dispatch(command(
                "platform.adapter.worker-observations.snapshot",
                "{\"workerIds\":[\"worker-1\"]}"
        )).outcomeCode()).isEqualTo(Integer.toString(
                WorkerDeliveryAdapterErrorCode.ADAPTER_EVENT_UNSUPPORTED.code()
        ));
    }

    @Test
    void propertiesEventAcceptsAtMostOneHundredUniqueWorkerIds() {
        WorkerConnectionMechanism connections = mock(
                WorkerConnectionMechanism.class
        );
        List<String> workerIds = new ArrayList<>();
        Map<String, WorkerPropertiesObservation> snapshots =
                new LinkedHashMap<>();
        for (int index = 0; index < 100; index++) {
            String workerId = "worker-" + index;
            workerIds.add(workerId);
            snapshots.put(workerId, new WorkerPropertiesObservation(
                    null,
                    null
            ));
        }
        when(connections.workerProperties(workerIds)).thenReturn(snapshots);
        AdapterEventDispatcher dispatcher = AdapterEventDispatcher.defaults(
                "adapter-1",
                connections
        );

        when(connections.workerProperties(List.of("worker-0")))
                .thenReturn(Map.of(
                        "worker-0",
                        new WorkerPropertiesObservation(
                                null,
                                null
                        )
                ));
        assertThat(dispatcher.dispatch(command(
                AdapterEventDispatcher.WORKER_PROPERTIES_SNAPSHOT_EVENT,
                "{\"workerIds\":[\"worker-0\"]}"
        )).outcomeCode()).isEqualTo("200");

        var accepted = dispatcher.dispatch(command(
                AdapterEventDispatcher.WORKER_PROPERTIES_SNAPSHOT_EVENT,
                Jsons.toJson(Map.of("workerIds", workerIds))
        ));
        assertThat(accepted.outcomeCode()).isEqualTo("200");

        List<String> tooMany = new ArrayList<>(workerIds);
        tooMany.add("worker-100");
        assertThat(dispatcher.dispatch(command(
                AdapterEventDispatcher.WORKER_PROPERTIES_SNAPSHOT_EVENT,
                Jsons.toJson(Map.of("workerIds", tooMany))
        )).outcomeCode()).isEqualTo(Integer.toString(
                WorkerDeliveryAdapterErrorCode.ADAPTER_COMMAND_INVALID.code()
        ));
        assertThat(dispatcher.dispatch(command(
                AdapterEventDispatcher.WORKER_PROPERTIES_SNAPSHOT_EVENT,
                "{\"workerIds\":[]}"
        )).outcomeCode()).isEqualTo(Integer.toString(
                WorkerDeliveryAdapterErrorCode.ADAPTER_COMMAND_INVALID.code()
        ));
        assertThat(dispatcher.dispatch(command(
                AdapterEventDispatcher.WORKER_PROPERTIES_SNAPSHOT_EVENT,
                "{\"workerIds\":[\"worker-1\",\"worker-1\"]}"
        )).outcomeCode()).isEqualTo(Integer.toString(
                WorkerDeliveryAdapterErrorCode.ADAPTER_COMMAND_INVALID.code()
        ));
    }

    private static DeliveryCommand command(String event, String payload) {
        return DeliveryCommand.create(
                SYSTEM,
                ADAPTER,
                event,
                System.currentTimeMillis() + 10_000,
                payload,
                "direct-call:v1:test"
        );
    }
}
