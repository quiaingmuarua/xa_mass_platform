package com.xa.mass.transport.polling.runtime;

import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.polling.worker.PollingWorkerAdapter;
import com.xa.mass.transport.route.TransportRouteOwnerStore;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.TransportRuntimeRegistry;
import com.xa.mass.transport.runtime.WorkerTransportRuntimeFactory;
import com.xa.mass.transport.runtime.delivery.DeliveryCommandConsumerRegistry;
import com.xa.mass.transport.runtime.delivery.NoopDeliveryCommandConsumerRegistry;
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
    public TransportRuntimeRegistry create(TaskResultIngestChannel taskResultIngestChannel,
                                           TransportRouteOwnerStore routeOwnerStore,
                                           TransportDeliveryService deliveryService,
                                           List<TransportBinding> adapterBindings) {
        return create(taskResultIngestChannel,
                routeOwnerStore,
                deliveryService,
                NoopDeliveryCommandConsumerRegistry.INSTANCE,
                "local",
                adapterBindings);
    }

    @Override
    public TransportRuntimeRegistry create(TaskResultIngestChannel taskResultIngestChannel,
                                           TransportRouteOwnerStore routeOwnerStore,
                                           TransportDeliveryService deliveryService,
                                           DeliveryCommandConsumerRegistry deliveryCommandConsumerRegistry,
                                           String deliveryCommandConsumerKey,
                                           List<TransportBinding> adapterBindings) {
        List<TransportBinding> bindings = new ArrayList<>(1 + (adapterBindings == null ? 0 : adapterBindings.size()));
        bindings.add(pollingBinding(
                routeOwnerStore,
                deliveryService,
                deliveryCommandConsumerRegistry,
                deliveryCommandConsumerKey
        ));
        if (adapterBindings != null && !adapterBindings.isEmpty()) {
            bindings.addAll(adapterBindings);
        }
        return new TransportRuntimeRegistry(
                taskResultIngestChannel,
                routeOwnerStore,
                deliveryCommandConsumerRegistry,
                deliveryCommandConsumerKey,
                bindings
        );
    }

    @Override
    public List<TransportAdapterDescriptor> registrationDescriptors() {
        return List.of(POLLING_DESCRIPTOR);
    }

    private static TransportBinding pollingBinding(TransportRouteOwnerStore routeOwnerStore,
                                                   TransportDeliveryService deliveryService,
                                                   DeliveryCommandConsumerRegistry deliveryCommandConsumerRegistry,
                                                   String deliveryCommandConsumerKey) {
        PollingWorkerAdapter pollingAdapter = new PollingWorkerAdapter(
                routeOwnerStore,
                deliveryService,
                deliveryCommandConsumerRegistry,
                deliveryCommandConsumerKey
        );
        return TransportBinding.builder(pollingAdapter)
                .taskPullChannel(pollingAdapter)
                .build();
    }
}
