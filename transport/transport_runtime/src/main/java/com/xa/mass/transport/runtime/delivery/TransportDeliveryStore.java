package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.TaskDispatchItem;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runtime-owned storage boundary for transport delivery handoff.
 */
public interface TransportDeliveryStore {

    DispatchOutcome enqueue(String adapterId, TaskDispatchItem item, int maxItemsPerWorker);

    List<TaskDispatchItem> drain(String adapterId, String workerId, int maxItems);

    List<TaskDispatchItem> poll(String adapterId,
                                String workerId,
                                int maxItems,
                                long timeout,
                                TimeUnit unit) throws InterruptedException;
}
