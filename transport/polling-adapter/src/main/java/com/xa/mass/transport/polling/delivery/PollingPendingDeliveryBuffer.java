package com.xa.mass.transport.polling.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.runtime.delivery.DispatchMessage;

import java.util.List;

/**
 * Polling-adapter-owned pending delivery buffer.
 *
 * <p>This is not the assigned-dispatch mailbox handoff. It is the polling
 * final-hop buffer that holds already accepted items until the authenticated
 * polling worker asks for them.
 *
 * <p>The worker id argument is the authenticated polling worker identity. It is
 * not a dispatch selector and does not let transport choose a worker.
 */
public interface PollingPendingDeliveryBuffer {

    List<DispatchOutcome> enqueue(String adapterMailboxKey, List<DispatchMessage> items);

    PollingPendingDeliveryPollResult poll(String adapterMailboxKey,
                                           String authenticatedWorkerId,
                                           int maxItems,
                                           long timeoutMillis) throws InterruptedException;

    void shutdown();
}
