package com.xa.mass.sdk.transport;

import com.xa.mass.transport.polling.worker.PollingWorkerAdapter;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.TransportRouteKeyResolvers;
import com.xa.mass.transport.runtime.TransportRuntimeRegistry;
import com.xa.mass.transport.runtime.WorkerTransportRuntimeFactory;
import com.xa.mass.transport.runtime.WorkerTransportRuntimeFactoryContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Default embedded-runtime transport assembly: always include polling/pull and
 * merge any additional adapter contribution assembled by runtime composition.
 */
public class DefaultWorkerTransportRuntimeFactory implements WorkerTransportRuntimeFactory {

    @Override
    public TransportRuntimeRegistry create(WorkerTransportRuntimeFactoryContext context) {
        List<TransportBinding> bindings = new ArrayList<>();

        PollingWorkerAdapter pollingAdapter = new PollingWorkerAdapter(
                context.getSystemEventChannel(),
                context.getDeliveryService()
        );
        bindings.add(TransportBinding.builder(pollingAdapter)
                .routeKeyResolver(TransportRouteKeyResolvers.workerId())
                .taskPullChannel(pollingAdapter)
                .build());
        bindings.addAll(context.getAdapterBindings());

        return new TransportRuntimeRegistry(
                context.getWorkerManager(),
                context.getTaskResultIngestChannel(),
                context.getSystemEventChannel(),
                bindings
        );
    }
}
