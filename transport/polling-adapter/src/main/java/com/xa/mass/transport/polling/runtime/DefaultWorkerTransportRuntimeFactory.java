package com.xa.mass.transport.polling.runtime;

import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.polling.worker.PollingWorkerAdapter;
import com.xa.mass.transport.route.TransportRouteOwnerStore;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.TransportRouteKeyResolver;
import com.xa.mass.transport.runtime.TransportRuntimeRegistry;
import com.xa.mass.transport.runtime.WorkerTransportRuntimeFactory;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Default embedded transport runtime: always provide the polling worker binding
 * and merge any additional adapter bindings assembled by runtime composition.
 */
public final class DefaultWorkerTransportRuntimeFactory implements WorkerTransportRuntimeFactory {

    private static final TransportAdapterDescriptor POLLING_DESCRIPTOR =
            new TransportAdapterDescriptor(PollingWorkerAdapter.PROTOCOL, PollingWorkerAdapter.PROTOCOL);

    @Override
    public TransportRuntimeRegistry create(TaskResultIngestChannel taskResultIngestChannel,
                                           WorkerSystemEventChannel systemEventChannel,
                                           TransportRouteOwnerStore routeOwnerStore,
                                           TransportDeliveryService deliveryService,
                                           TransportRouteKeyResolver routeKeyResolver,
                                           List<TransportBinding> adapterBindings) {
        List<TransportBinding> bindings = new ArrayList<>(1 + (adapterBindings == null ? 0 : adapterBindings.size()));
        bindings.add(pollingBinding(routeOwnerStore, deliveryService, routeKeyResolver));
        if (adapterBindings != null && !adapterBindings.isEmpty()) {
            bindings.addAll(adapterBindings);
        }
        return new TransportRuntimeRegistry(
                taskResultIngestChannel,
                systemEventChannel,
                routeOwnerStore,
                bindings
        );
    }

    @Override
    public List<TransportAdapterDescriptor> registrationDescriptors() {
        return List.of(POLLING_DESCRIPTOR);
    }

    private static TransportBinding pollingBinding(TransportRouteOwnerStore routeOwnerStore,
                                                   TransportDeliveryService deliveryService,
                                                   TransportRouteKeyResolver routeKeyResolver) {
        PollingWorkerAdapter pollingAdapter = new PollingWorkerAdapter(routeOwnerStore, deliveryService);
        return TransportBinding.builder(pollingAdapter)
                .routeKeyResolver(Objects.requireNonNull(routeKeyResolver, "routeKeyResolver"))
                .taskPullChannel(pollingAdapter)
                .build();
    }
}
