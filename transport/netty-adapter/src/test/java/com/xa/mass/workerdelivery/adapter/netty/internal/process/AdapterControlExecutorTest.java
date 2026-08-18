package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.SYSTEM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionMechanism;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionState;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerObservationSnapshot;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdapterControlExecutorTest {

    @Test
    void staticallyAssembledHandlerMapDispatchesCustomEvent() {
        Map<String, AdapterControlExecutor.AdapterControlHandler> handlers =
                new LinkedHashMap<>();
        handlers.put("platform.adapter.custom", payload -> payload);
        AdapterControlExecutor executor = new AdapterControlExecutor(
                "adapter-1",
                handlers
        );
        handlers.clear();

        var report = executor.execute(command(
                "platform.adapter.custom",
                "{\"value\":1}"
        ));

        assertThat(report.outcomeCode()).isEqualTo("200");
        assertThat(report.payload()).isEqualTo("{\"value\":1}");
    }

    @Test
    void eventSnapshotDescribesTheExactImmutableHandlerMap() {
        Map<String, AdapterControlExecutor.AdapterControlHandler> handlers =
                new LinkedHashMap<>();
        handlers.put("platform.adapter.zeta", payload -> payload);
        handlers.put("platform.adapter.alpha", payload -> payload);
        AdapterControlExecutor executor = new AdapterControlExecutor(
                "adapter-1",
                handlers
        );
        handlers.clear();

        var report = executor.execute(command(
                AdapterControlExecutor.EVENTS_SNAPSHOT_EVENT,
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
        assertThatThrownBy(() -> new AdapterControlExecutor(
                "adapter-1",
                Map.of(
                        AdapterControlExecutor.EVENTS_SNAPSHOT_EVENT,
                        payload -> payload
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");

        AdapterControlExecutor executor = new AdapterControlExecutor(
                "adapter-1",
                Map.of()
        );
        assertThat(executor.execute(command(
                AdapterControlExecutor.EVENTS_SNAPSHOT_EVENT,
                "{}"
        )).outcomeCode()).isEqualTo(Integer.toString(
                WorkerDeliveryAdapterErrorCode.CONTROL_COMMAND_INVALID.code()
        ));
    }

    @Test
    void handlerFailureIsAnObservedExecutionFailure() {
        AdapterControlExecutor executor = new AdapterControlExecutor(
                "adapter-1",
                Map.of("platform.adapter.failure", payload -> {
                    throw new Exception("failed");
                })
        );

        var report = executor.execute(command(
                "platform.adapter.failure",
                "null"
        ));

        assertThat(report.outcomeCode()).isEqualTo(Integer.toString(
                WorkerDeliveryAdapterErrorCode
                        .CONTROL_EVENT_EXECUTION_FAILED.code()
        ));
        assertThat(report.payload()).isEqualTo("null");
    }

    @Test
    void handlerIllegalArgumentFailureIsNotMisclassifiedAsInvalidPayload() {
        AdapterControlExecutor executor = new AdapterControlExecutor(
                "adapter-1",
                Map.of("platform.adapter.failure", payload -> {
                    throw new IllegalArgumentException("handler bug");
                })
        );

        var report = executor.execute(command(
                "platform.adapter.failure",
                "null"
        ));

        assertThat(report.outcomeCode()).isEqualTo(Integer.toString(
                WorkerDeliveryAdapterErrorCode
                        .CONTROL_EVENT_EXECUTION_FAILED.code()
        ));
    }

    @Test
    void handlerMapRejectsNonPlatformAdapterEventNames() {
        assertThatThrownBy(() -> new AdapterControlExecutor(
                "adapter-1",
                Map.of("extension.adapter.custom", payload -> payload)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("platform.adapter");
    }

    @Test
    void defaultObservationEventReturnsOrderedRouteAndPropertyProjection() {
        WorkerConnectionMechanism connections = mock(
                WorkerConnectionMechanism.class
        );
        Map<String, WorkerObservationSnapshot> snapshots =
                new LinkedHashMap<>();
        snapshots.put(
                "worker-1",
                new WorkerObservationSnapshot(
                        WorkerConnectionState.CONNECTED,
                        WorkerObservationSnapshot.PropertiesFreshness.FRESH,
                        new WorkerObservationSnapshot.PropertiesVersion(
                                "epoch-1",
                                3L
                        ),
                        123L,
                        Map.of("battery", 87L)
                )
        );
        snapshots.put(
                "stale",
                new WorkerObservationSnapshot(
                        WorkerConnectionState.DISCONNECTED,
                        WorkerObservationSnapshot.PropertiesFreshness.STALE,
                        new WorkerObservationSnapshot.PropertiesVersion(
                                "epoch-1",
                                2L
                        ),
                        100L,
                        Map.of("battery", 40L)
                )
        );
        snapshots.put(
                "unknown",
                new WorkerObservationSnapshot(
                        WorkerConnectionState.UNKNOWN,
                        WorkerObservationSnapshot.PropertiesFreshness.UNKNOWN,
                        null,
                        null,
                        null
                )
        );
        when(connections.workerObservations(List.of(
                "worker-1",
                "stale",
                "unknown"
        ))).thenReturn(snapshots);
        AdapterControlExecutor executor = AdapterControlExecutor.defaults(
                "adapter-1",
                connections
        );

        var report = executor.execute(command(
                AdapterControlExecutor.WORKER_OBSERVATIONS_SNAPSHOT_EVENT,
                "{\"workerIds\":[\"worker-1\",\"stale\",\"unknown\"]}"
        ));

        assertThat(report.outcomeCode()).isEqualTo("200");
        @SuppressWarnings("unchecked")
        Map<String, Object> observations = (Map<String, Object>) Jsons
                .parseObject(report.payload())
                .get("observationsByWorkerId");
        assertThat(observations.keySet()).containsExactly(
                "worker-1",
                "stale",
                "unknown"
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> connected = (Map<String, Object>) observations
                .get("worker-1");
        assertThat(connected)
                .containsEntry("connectionState", "CONNECTED")
                .containsEntry("propertiesFreshness", "FRESH")
                .containsEntry("propertiesObservedAtMillis", 123L)
                .doesNotContainKey("workerGroupId");
        @SuppressWarnings("unchecked")
        Map<String, Object> stale = (Map<String, Object>) observations
                .get("stale");
        assertThat(stale)
                .containsEntry("connectionState", "DISCONNECTED")
                .containsEntry("propertiesFreshness", "STALE")
                .containsEntry("properties", Map.of("battery", 40L));
        @SuppressWarnings("unchecked")
        Map<String, Object> unknown = (Map<String, Object>) observations
                .get("unknown");
        assertThat(unknown)
                .containsEntry("connectionState", "UNKNOWN")
                .containsEntry("propertiesFreshness", "UNKNOWN")
                .containsEntry("propertiesVersion", null)
                .containsEntry("properties", null);

        var events = executor.execute(command(
                AdapterControlExecutor.EVENTS_SNAPSHOT_EVENT,
                "null"
        ));
        @SuppressWarnings("unchecked")
        List<String> eventNames = (List<String>) Jsons.parseObject(
                events.payload()
        ).get("eventNames");
        assertThat(eventNames)
                .contains(
                        AdapterControlExecutor
                                .WORKER_OBSERVATIONS_SNAPSHOT_EVENT
                );
    }

    @Test
    void observationEventAcceptsAtMostOneHundredUniqueWorkerIds() {
        WorkerConnectionMechanism connections = mock(
                WorkerConnectionMechanism.class
        );
        List<String> workerIds = new ArrayList<>();
        Map<String, WorkerObservationSnapshot> snapshots =
                new LinkedHashMap<>();
        for (int index = 0; index < 100; index++) {
            String workerId = "worker-" + index;
            workerIds.add(workerId);
            snapshots.put(workerId, new WorkerObservationSnapshot(
                    WorkerConnectionState.UNKNOWN,
                    WorkerObservationSnapshot.PropertiesFreshness.UNKNOWN,
                    null,
                    null,
                    null
            ));
        }
        when(connections.workerObservations(workerIds)).thenReturn(snapshots);
        AdapterControlExecutor executor = AdapterControlExecutor.defaults(
                "adapter-1",
                connections
        );

        var accepted = executor.execute(command(
                AdapterControlExecutor.WORKER_OBSERVATIONS_SNAPSHOT_EVENT,
                Jsons.toJson(Map.of("workerIds", workerIds))
        ));
        assertThat(accepted.outcomeCode()).isEqualTo("200");

        List<String> tooMany = new ArrayList<>(workerIds);
        tooMany.add("worker-100");
        assertThat(executor.execute(command(
                AdapterControlExecutor.WORKER_OBSERVATIONS_SNAPSHOT_EVENT,
                Jsons.toJson(Map.of("workerIds", tooMany))
        )).outcomeCode()).isEqualTo(Integer.toString(
                WorkerDeliveryAdapterErrorCode.CONTROL_COMMAND_INVALID.code()
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
