package com.xa.mass.transport.polling.runtime;

import com.xa.mass.transport.channel.TransportResultIngressChannel;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.TransportRuntimeRegistry;
import com.xa.mass.transport.runtime.WorkerTransportRuntimeFactory;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;

import java.util.List;

/**
 * Default embedded transport runtime registry factory.
 *
 * <p>Adapter bindings, including the default polling binding, are contributed
 * by runtime-composition bootstraps before this factory creates the local
 * runtime registry.
 */
public final class DefaultWorkerTransportRuntimeFactory implements WorkerTransportRuntimeFactory {

    @Override
    public TransportRuntimeRegistry create(TransportResultIngressChannel resultIngressChannel,
                                           TransportEndpointLeaseStore endpointLeaseStore,
                                           TransportDeliveryService deliveryService,
                                           List<TransportBinding> adapterBindings) {
        List<TransportBinding> bindings = adapterBindings == null
                ? List.of()
                : List.copyOf(adapterBindings);
        return new TransportRuntimeRegistry(
                resultIngressChannel,
                endpointLeaseStore,
                bindings
        );
    }
}
