package com.xa.mass.transport.runtime.embedded;

import com.xa.mass.transport.runtime.ManagedTransportAdapter;

/**
 * Adapter-owned mailbox poll-loop resource.
 */
public interface AdapterMailboxConsumer extends ManagedTransportAdapter {

    String adapterMailboxKey();
}
