package com.xa.mass.client.worker.session;

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
    static DispatchContext from(WorkerDispatchItem item) {
        return new DispatchContext(
                item.taskId(),
                item.messageId(),
                item.eventCode(),
                item.workerId(),
                MassPayload.of(item.input()),
                MassPayload.of(item.sharedConfig()),
                item
        );
    }
}
