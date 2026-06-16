package com.xa.mass.transport.runtime;

import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.transport.runtime.delivery.DeliveryCommandConsumerRegistry;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;

import java.util.List;

/**
 * Factory seam for assembling the set of worker transport bindings used by an
 * embedded runtime.
 */
public interface WorkerTransportRuntimeFactory {

    TransportRuntimeRegistry create(TaskResultIngestChannel taskResultIngestChannel,
                                    TransportEndpointLeaseStore endpointLeaseStore,
                                    TransportDeliveryService deliveryService,
                                    List<TransportBinding> adapterBindings);

    default TransportRuntimeRegistry create(TaskResultIngestChannel taskResultIngestChannel,
                                            TransportEndpointLeaseStore endpointLeaseStore,
                                            TransportDeliveryService deliveryService,
                                            DeliveryCommandConsumerRegistry deliveryCommandConsumerRegistry,
                                            String deliveryCommandConsumerKey,
                                            List<TransportBinding> adapterBindings) {
        return create(taskResultIngestChannel, endpointLeaseStore, deliveryService, adapterBindings);
    }

    default List<TransportAdapterDescriptor> registrationDescriptors() {
        return List.of();
    }
}
