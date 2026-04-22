package com.xa.mass.transport.channel;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;

import java.util.List;

/**
 * Transport-neutral channel for dispatching logical task messages to workers.
 */
public interface TaskDispatchChannel {

    void dispatchTaskMessages(Task task, List<TaskMsg> taskMsgs);
}
