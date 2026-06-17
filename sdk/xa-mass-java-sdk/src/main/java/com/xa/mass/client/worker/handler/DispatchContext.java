package com.xa.mass.client.worker.handler;

import com.xa.mass.client.payload.MassPayload;
import com.xa.mass.client.worker.WorkerDispatchItem;

public record DispatchContext(
        String taskId,
        String messageId,
        String eventCode,
        String workerId,
        MassPayload input,
        MassPayload sharedConfig,
        WorkerDispatchItem rawItem
) {
    public static DispatchContext from(WorkerDispatchItem item) {
        return from(item, item.workerId());
    }

    public static DispatchContext from(WorkerDispatchItem item, String workerId) {
        return new DispatchContext(
                item.taskId(),
                item.messageId(),
                item.eventCode(),
                firstNonBlank(item.workerId(), workerId),
                MassPayload.of(item.input()),
                MassPayload.of(item.sharedConfig()),
                item
        );
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        return fallback == null ? null : fallback.trim();
    }
}
