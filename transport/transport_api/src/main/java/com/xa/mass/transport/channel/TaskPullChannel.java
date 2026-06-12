package com.xa.mass.transport.channel;

import java.util.List;

/**
 * Pull-based task intake channel for polling workers.
 */
public interface TaskPullChannel {

    default List<PulledTaskDispatch> pollTaskMessages(String selectedWorkerId, int maxMessages) {
        return pollTaskMessages(selectedWorkerId, maxMessages, 0L);
    }

    default List<PulledTaskDispatch> pollTaskMessages(String selectedWorkerId, int maxMessages, long timeoutMillis) {
        return pollTaskMessagesResult(selectedWorkerId, maxMessages, timeoutMillis).getItems();
    }

    default TaskPullResult pollTaskMessagesResult(String selectedWorkerId, int maxMessages) {
        return pollTaskMessagesResult(selectedWorkerId, maxMessages, 0L);
    }

    TaskPullResult pollTaskMessagesResult(String selectedWorkerId, int maxMessages, long timeoutMillis);
}
