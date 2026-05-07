package com.xa.mass.sdk.transport;

import com.xa.mass.transport.polling.worker.PollingWorkerAdapter;
import com.xa.mass.storage.api.WorkerLookupStore;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.TransportRuntimeRegistry;
import com.xa.mass.transport.runtime.WorkerTransportRuntimeFactory;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;

import java.util.ArrayList;
import java.util.List;

/**
 * Default embedded-runtime transport assembly: always include polling/pull and
 * merge any additional adapter contribution assembled by runtime composition.
 */
public class DefaultWorkerTransportRuntimeFactory implements WorkerTransportRuntimeFactory {

    @Override
    public TransportRuntimeRegistry create(WorkerLookupStore workerLookupStore,
                                           TaskResultIngestChannel taskResultIngestChannel,
                                           WorkerSystemEventChannel systemEventChannel,
                                           TransportDeliveryService deliveryService,
                                           List<TransportBinding> adapterBindings) {
        List<TransportBinding> bindings = new ArrayList<>();

        PollingWorkerAdapter pollingAdapter = new PollingWorkerAdapter(
                systemEventChannel,
                deliveryService
        );
        bindings.add(TransportBinding.builder(pollingAdapter)
                .routeKeyResolver((dispatchBinding, routeContext) -> {
                    if (routeContext != null && routeContext.workerId() != null && !routeContext.workerId().isBlank()) {
                        return routeContext.workerId();
                    }
                    return dispatchBinding != null ? dispatchBinding.workerId() : null;
                })
                .taskPullChannel(pollingAdapter)
                .build());
        bindings.addAll(adapterBindings);

        return new TransportRuntimeRegistry(
                workerLookupStore,
                taskResultIngestChannel,
                systemEventChannel,
                bindings
        );
    }
}
