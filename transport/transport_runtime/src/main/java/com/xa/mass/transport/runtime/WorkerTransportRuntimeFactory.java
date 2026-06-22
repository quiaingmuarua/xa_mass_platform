package com.xa.mass.transport.runtime;

import com.xa.mass.transport.channel.TransportResultIngressChannel;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;

import java.util.List;

/**
 * Factory seam for assembling the set of worker transport bindings used by an
 * embedded runtime.
 */
public interface WorkerTransportRuntimeFactory {

    TransportRuntimeRegistry create(TransportResultIngressChannel resultIngressChannel,
                                    TransportEndpointLeaseStore endpointLeaseStore,
                                    List<TransportBinding> adapterBindings);

    default List<TransportAdapterDescriptor> registrationDescriptors() {
        return List.of();
    }
}
