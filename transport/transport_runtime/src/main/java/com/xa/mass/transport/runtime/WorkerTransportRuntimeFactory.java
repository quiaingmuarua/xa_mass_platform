package com.xa.mass.transport.runtime;

import com.xa.mass.runtime.worker.WorkerResourceRuntime;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.presence.WorkerPresenceStore;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;

import java.util.List;

/**
 * Factory seam for assembling the set of worker transport bindings used by an
 * embedded runtime.
 */
public interface WorkerTransportRuntimeFactory {

    TransportRuntimeRegistry create(WorkerResourceRuntime workerResourceRuntime,
                                    TaskResultIngestChannel taskResultIngestChannel,
                                    WorkerSystemEventChannel systemEventChannel,
                                    WorkerPresenceStore workerPresenceStore,
                                    TransportDeliveryService deliveryService,
                                    List<TransportBinding> adapterBindings);

    default List<TransportAdapterDescriptor> registrationDescriptors() {
        return List.of();
    }
}
