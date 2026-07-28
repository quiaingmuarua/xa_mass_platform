package com.xa.mass.server.workerdelivery.adapter;

import com.xa.mass.workerdelivery.adapter.application.WebSocketWorkerDeliveryAdapterConfig;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterDefinition;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterRuntimeConfig;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterType;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(
        prefix = "xa.mass.worker-delivery.adapter",
        ignoreUnknownFields = false
)
public record ServerWorkerDeliveryAdapterProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("WEBSOCKET") WorkerDeliveryAdapterType type,
        @DefaultValue RuntimeProperties runtime,
        @DefaultValue WebSocketProperties websocket
) {

    public ServerWorkerDeliveryAdapterProperties {
        if (type == null) {
            throw new IllegalArgumentException(
                    "Adapter type must be configured"
            );
        }
        if (runtime == null) {
            throw new IllegalArgumentException(
                    "Adapter runtime config must be configured"
            );
        }
        if (websocket == null) {
            throw new IllegalArgumentException(
                    "WebSocket Adapter config must be configured"
            );
        }
        if (enabled) {
            definition(type, runtime, websocket);
        }
    }

    public WorkerDeliveryAdapterDefinition definition() {
        if (!enabled) {
            throw new IllegalStateException(
                    "Worker Delivery Adapter is disabled"
            );
        }
        return definition(type, runtime, websocket);
    }

    private static WorkerDeliveryAdapterDefinition definition(
            WorkerDeliveryAdapterType type,
            RuntimeProperties runtime,
            WebSocketProperties websocket
    ) {
        WorkerDeliveryAdapterRuntimeConfig runtimeConfig =
                new WorkerDeliveryAdapterRuntimeConfig(
                        runtime.endpointManagerId(),
                        runtime.gatewayBaseUrl(),
                        runtime.requestTimeout(),
                        runtime.dispatchInterval(),
                        runtime.scanCount(),
                        runtime.resultBatchSize(),
                        runtime.resultBufferCapacity()
                );
        return switch (type) {
            case WEBSOCKET -> new WorkerDeliveryAdapterDefinition(
                    type,
                    runtimeConfig,
                    new WebSocketWorkerDeliveryAdapterConfig(
                            websocket.sendTimeLimit()
                    )
            );
        };
    }

    public record RuntimeProperties(
            @DefaultValue("") String endpointManagerId,
            @DefaultValue("http://127.0.0.1:18082") URI gatewayBaseUrl,
            @DefaultValue("5s") Duration requestTimeout,
            @DefaultValue("100ms") Duration dispatchInterval,
            @DefaultValue("100") int scanCount,
            @DefaultValue("100") int resultBatchSize,
            @DefaultValue("1000") int resultBufferCapacity
    ) {
    }

    public record WebSocketProperties(
            @DefaultValue("5s") Duration sendTimeLimit
    ) {
    }
}
