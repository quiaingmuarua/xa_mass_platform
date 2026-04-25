package com.xa.mass.transport.channel;

import com.xa.mass.transport.model.TaskDispatchItem;

import java.util.List;

/**
 * Transport-neutral channel for dispatching logical task items to workers.
 */
public interface TaskDispatchChannel {

    void dispatchTaskItems(List<TaskDispatchItem> items);
}
