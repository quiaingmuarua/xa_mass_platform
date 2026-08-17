package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.SYSTEM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import java.util.List;
import java.util.LinkedHashMap;
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
