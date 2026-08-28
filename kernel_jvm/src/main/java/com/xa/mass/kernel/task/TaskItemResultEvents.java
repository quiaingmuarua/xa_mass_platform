package com.xa.mass.kernel.task;

import java.util.List;
import java.util.Map;

/** Semantic Mechanism port for bounded TaskItem result events. */
public interface TaskItemResultEvents {

    void onItemsSucceeded(
            String taskId,
            Map<String, String> payloadsByMessageId,
            long observedAtMillis
    );

    void onItemsFailed(
            String taskId,
            List<String> messageIds,
            long observedAtMillis
    );
}
