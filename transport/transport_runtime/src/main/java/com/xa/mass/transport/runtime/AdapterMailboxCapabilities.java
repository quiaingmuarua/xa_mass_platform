package com.xa.mass.transport.runtime;

import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutor;
import com.xa.mass.transport.runtime.embedded.AdapterMailboxConsumer;

/**
 * Host-owned adapter mailbox support exposed to a concrete adapter bootstrap.
 */
public interface AdapterMailboxCapabilities {

    String assignedMailboxKey();

    AdapterMailboxConsumer consumer(String consumerId, AdapterCommandExecutor commandExecutor);
}
