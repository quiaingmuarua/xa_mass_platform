package com.xa.mass.workerdelivery.adapter.websocket;

import com.xa.mass.workerdelivery.adapter.application.InMemoryWorkerConnectionRegistry;
import com.xa.mass.workerdelivery.adapter.application.ScheduledWorkerDeliveryAdapter;
import com.xa.mass.workerdelivery.adapter.application.WebSocketWorkerDeliveryAdapterConfig;
import com.xa.mass.workerdelivery.adapter.application.WorkerConnectionRegistry;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterCore;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterFactory;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterRuntimeConfig;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterType;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.adapter.http.HttpWorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import java.util.Objects;

public final class WebSocketWorkerDeliveryAdapterFactory
        implements WorkerDeliveryAdapterFactory<
        WebSocketWorkerDeliveryAdapterConfig> {

    private final WorkerDeliveryCodec codec;
    private WorkerWebSocketHandler handler;

    public WebSocketWorkerDeliveryAdapterFactory(
            WorkerDeliveryCodec codec
    ) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public WorkerDeliveryAdapterType adapterType() {
        return WorkerDeliveryAdapterType.WEBSOCKET;
    }

    @Override
    public Class<WebSocketWorkerDeliveryAdapterConfig>
    privateConfigType() {
        return WebSocketWorkerDeliveryAdapterConfig.class;
    }

    @Override
    public synchronized WorkerDeliveryAdapter create(
            WorkerDeliveryAdapterRuntimeConfig runtimeConfig,
            WebSocketWorkerDeliveryAdapterConfig privateConfig
    ) {
        Objects.requireNonNull(runtimeConfig, "runtimeConfig");
        Objects.requireNonNull(privateConfig, "privateConfig");
        if (handler != null) {
            throw new IllegalStateException(
                    "WebSocket Adapter factory supports one instance"
            );
        }
        WorkerDeliveryGatewayClient gateway =
                new HttpWorkerDeliveryGatewayClient(
                        runtimeConfig.gatewayBaseUrl(),
                        runtimeConfig.requestTimeout(),
                        codec
                );
        WorkerConnectionRegistry connections =
                new InMemoryWorkerConnectionRegistry();
        WorkerDeliveryAdapterCore core =
                new WorkerDeliveryAdapterCore(
                        gateway,
                        codec,
                        connections,
                        runtimeConfig.endpointManagerId(),
                        runtimeConfig.scanCount(),
                        runtimeConfig.resultBatchSize(),
                        runtimeConfig.resultBufferCapacity()
                );
        WorkerDeliveryAdapter adapter =
                new ScheduledWorkerDeliveryAdapter(
                        adapterType(),
                        runtimeConfig.endpointManagerId(),
                        runtimeConfig.dispatchInterval(),
                        core
                );
        handler = new WorkerWebSocketHandler(
                codec,
                core,
                adapter,
                privateConfig.sendTimeLimit()
        );
        return adapter;
    }

    public synchronized WorkerWebSocketHandler handler() {
        if (handler == null) {
            throw new IllegalStateException(
                    "WebSocket Adapter is not registered"
            );
        }
        return handler;
    }
}
