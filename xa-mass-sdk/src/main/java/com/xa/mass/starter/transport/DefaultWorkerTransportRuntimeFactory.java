package com.xa.mass.starter.transport;

import com.xa.mass.starter.worker.PollingWorkerAdapter;
import com.xa.mass.starter.worker.WebSocketWorkerAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Default embedded-runtime transport assembly: pull/polling plus the current
 * WebSocket adapter when the gateway is enabled.
 *
 * <p>The WebSocket adapter now speaks canonical root-level task/control JSON
 * frames only. New control or business capabilities must be added as global
 * SDK events instead of transport-specific routing branches here.
 */
public class DefaultWorkerTransportRuntimeFactory implements WorkerTransportRuntimeFactory {

    @Override
    public TransportRuntimeRegistry create(WorkerTransportRuntimeFactoryContext context) {
        List<TransportBinding> bindings = new ArrayList<>();

        PollingWorkerAdapter pollingAdapter = new PollingWorkerAdapter(context.getSystemEventChannel());
        bindings.add(TransportBinding.builder(pollingAdapter)
                .taskPullChannel(pollingAdapter)
                .build());

        if (context.isGatewayEnabled()) {
            WebSocketWorkerAdapter webSocketAdapter = new WebSocketWorkerAdapter(
                    context.getMessageTransporter(),
                    context.getFrameCodec()
            );
            bindings.add(TransportBinding.builder(webSocketAdapter).build());
        }

        return new TransportRuntimeRegistry(
                context.getWorkerManager(),
                context.getTaskResultIngestChannel(),
                context.getSystemEventChannel(),
                bindings
        );
    }
}
