package com.xa.mass.transport.polling.runtime;

import com.xa.mass.storage.api.WorkerLookupStore;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.polling.worker.PollingWorkerAdapter;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.TransportRouteKeyResolvers;
import com.xa.mass.transport.runtime.TransportRuntimeRegistry;
import com.xa.mass.transport.runtime.WorkerTransportRuntimeFactory;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;

import java.util.ArrayList;
import java.util.List;

/**
 * Default embedded transport runtime: always provide the polling worker binding
 * and merge any additional adapter bindings assembled by runtime composition.
 */
public final class DefaultWorkerTransportRuntimeFactory implements WorkerTransportRuntimeFactory {

    private static final TransportAdapterDescriptor POLLING_DESCRIPTOR =
            new TransportAdapterDescriptor(PollingWorkerAdapter.PROTOCOL, PollingWorkerAdapter.PROTOCOL);

    @Override
    public TransportRuntimeRegistry create(WorkerLookupStore workerLookupStore,
                                           TaskResultIngestChannel taskResultIngestChannel,
                                           WorkerSystemEventChannel systemEventChannel,
                                           TransportDeliveryService deliveryService,
                                           List<TransportBinding> adapterBindings) {
        List<TransportBinding> bindings = new ArrayList<>(1 + (adapterBindings == null ? 0 : adapterBindings.size()));
        bindings.add(pollingBinding(systemEventChannel, deliveryService));
        if (adapterBindings != null && !adapterBindings.isEmpty()) {
            bindings.addAll(adapterBindings);
        }
        return new TransportRuntimeRegistry(
                workerLookupStore,
                taskResultIngestChannel,
                systemEventChannel,
                bindings
        );
    }

    @Override
    public List<TransportAdapterDescriptor> registrationDescriptors() {
        return List.of(POLLING_DESCRIPTOR);
    }

    private static TransportBinding pollingBinding(WorkerSystemEventChannel systemEventChannel,
                                                   TransportDeliveryService deliveryService) {
        PollingWorkerAdapter pollingAdapter = new PollingWorkerAdapter(systemEventChannel, deliveryService);
        return TransportBinding.builder(pollingAdapter)
                .routeKeyResolver(TransportRouteKeyResolvers.workerId())
                .taskPullChannel(pollingAdapter)
                .build();
    }
}
