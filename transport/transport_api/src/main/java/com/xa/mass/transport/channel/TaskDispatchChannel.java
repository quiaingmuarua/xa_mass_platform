package com.xa.mass.transport.channel;

import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.DispatchOutcome;

import java.util.List;

/**
 * Transport-neutral channel for dispatching logical task items to workers.
 */
public interface TaskDispatchChannel {

    List<DispatchOutcome> dispatchTaskItems(List<TaskDispatchItem> items);
}
