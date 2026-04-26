package com.xa.mass.transport.channel;

import com.xa.mass.transport.model.TaskDispatchItem;

import java.util.List;

/**
 * Pull-based task intake channel for polling workers.
 */
public interface TaskPullChannel {

    default List<TaskDispatchItem> pollTaskMessages(String workerId, int maxMessages) {
        return pollTaskMessages(workerId, maxMessages, 0L);
    }

    List<TaskDispatchItem> pollTaskMessages(String workerId, int maxMessages, long timeoutMillis);
}
