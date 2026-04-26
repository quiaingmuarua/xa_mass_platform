package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.TaskDispatchItem;

import java.util.Objects;

/**
 * Runtime-owned delivery record for a task dispatch handed to a transport.
 */
public final class TransportDelivery {

    private final String adapterId;
    private final String workerId;
    private final TaskDispatchItem dispatchItem;
    private final long createdAtEpochMillis;

    public TransportDelivery(String adapterId,
                             String workerId,
                             TaskDispatchItem dispatchItem,
                             long createdAtEpochMillis) {
        this.adapterId = normalize(adapterId);
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.dispatchItem = Objects.requireNonNull(dispatchItem, "dispatchItem");
        this.createdAtEpochMillis = createdAtEpochMillis;
    }

    public String getAdapterId() {
        return adapterId;
    }

    public String getWorkerId() {
        return workerId;
    }

    public TaskDispatchItem getDispatchItem() {
        return dispatchItem;
    }

    public long getCreatedAtEpochMillis() {
        return createdAtEpochMillis;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
