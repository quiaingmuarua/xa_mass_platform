package com.xa.mass.kernel.task;

import java.util.Map;

/** Semantic Mechanism port for bounded TaskItem result events. */
public interface TaskItemResultEvents {

    void onItemsSucceeded(
            String taskId,
            Map<String, String> payloadsByMessageId,
            long observedAtMillis
    );
}
