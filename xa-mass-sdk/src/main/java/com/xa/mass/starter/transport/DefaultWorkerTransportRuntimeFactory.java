package com.xa.mass.starter.transport;

import com.xa.mass.gateway.runtime.GatewayEmbeddedRuntimeSupport;
import com.xa.mass.starter.worker.PollingWorkerAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Default embedded-runtime transport assembly: pull/polling plus the current
 * gateway-backed realtime adapter when the gateway is enabled.
 */
public class DefaultWorkerTransportRuntimeFactory implements WorkerTransportRuntimeFactory {

    @Override
    public TransportRuntimeRegistry create(WorkerTransportRuntimeFactoryContext<?> context) {
        List<TransportBinding> bindings = new ArrayList<>();

        PollingWorkerAdapter pollingAdapter = new PollingWorkerAdapter(context.getSystemEventChannel());
        bindings.add(TransportBinding.builder(pollingAdapter)
                .taskPullChannel(pollingAdapter)
                .build());

        if (context.isGatewayEnabled()) {
            bindings.add(TransportBinding.builder(
                    GatewayEmbeddedRuntimeSupport.createRealtimeWorkerAdapter(context.getTaskDispatchChannel())
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
