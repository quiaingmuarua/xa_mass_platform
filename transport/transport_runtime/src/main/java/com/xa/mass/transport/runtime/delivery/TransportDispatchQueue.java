package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;

import java.util.List;

/**
 * Direct keyed queue for already-assigned dispatch messages.
 */
public interface TransportDispatchQueue {

    List<DispatchOutcome> offer(String dispatchQueueKey, List<DispatchMessage> items);

    List<DispatchMessage> poll(String dispatchQueueKey,
                               int maxItems,
                               long timeoutMillis) throws InterruptedException;

    void shutdown();
}
