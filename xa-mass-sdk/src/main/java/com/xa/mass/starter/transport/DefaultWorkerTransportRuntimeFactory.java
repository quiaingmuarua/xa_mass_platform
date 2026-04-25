package com.xa.mass.starter.transport;

import com.xa.mass.gateway.runtime.WebSocketEmbeddedRuntimeSupport;
import com.xa.mass.starter.worker.PollingWorkerAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Default embedded-runtime transport assembly: pull/polling plus the current
 * WebSocket-backed realtime adapter when the WebSocket adapter is enabled.
 */
public class DefaultWorkerTransportRuntimeFactory implements WorkerTransportRuntimeFactory {

    @Override
    public TransportRuntimeRegistry create(WorkerTransportRuntimeFactoryContext<?> context) {
        List<TransportBinding> bindings = new ArrayList<>();

        PollingWorkerAdapter pollingAdapter = new PollingWorkerAdapter(context.getSystemEventChannel());
        bindings.add(TransportBinding.builder(pollingAdapter)
                .taskPullChannel(pollingAdapter)
                .build());

        if (context.isWebSocketEnabled()) {
            bindings.add(TransportBinding.builder(
                    WebSocketEmbeddedRuntimeSupport.createRealtimeWorkerAdapter(context.getTaskDispatchChannel())
            ).build());
        }

        return new TransportRuntimeRegistry(
                context.getWorkerManager(),
                context.getTaskResultIngestChannel(),
                context.getSystemEventChannel(),
                bindings
        );
    }
}
