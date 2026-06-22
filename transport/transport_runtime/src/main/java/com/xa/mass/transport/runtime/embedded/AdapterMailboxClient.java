package com.xa.mass.transport.runtime.embedded;

import com.xa.mass.transport.runtime.delivery.DispatchMessage;

import java.util.List;

/**
 * Adapter-side mailbox poll view over the transport dispatch handoff.
 */
@FunctionalInterface
public interface AdapterMailboxClient {

    List<DispatchMessage> poll(String adapterMailboxKey,
                                   int maxItems,
                                   long timeoutMillis) throws InterruptedException;
}
