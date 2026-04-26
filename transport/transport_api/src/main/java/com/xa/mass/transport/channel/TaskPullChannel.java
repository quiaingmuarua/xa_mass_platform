package com.xa.mass.transport.channel;

import com.xa.mass.transport.model.TaskDispatchItem;

import java.util.List;

/**
 * Pull-based task intake channel for polling workers.
 */
public interface TaskPullChannel {

    List<TaskDispatchItem> pollTaskMessages(String workerId, int maxMessages);
}
