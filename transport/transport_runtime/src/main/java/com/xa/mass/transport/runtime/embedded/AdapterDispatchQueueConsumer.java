package com.xa.mass.transport.runtime.embedded;

import com.xa.mass.transport.runtime.ManagedTransportAdapter;

/**
 * Adapter-owned dispatch queue consumer resource.
 */
public interface AdapterDispatchQueueConsumer extends ManagedTransportAdapter {

    String dispatchQueueKey();
}
