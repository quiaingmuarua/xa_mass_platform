package com.xa.mass.engine.listener;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;

import java.util.List;

@FunctionalInterface
public interface TaskMsgDispatchListener {
    void onTaskMsgsReady(Task task, List<TaskMsg> taskMsgs);
}
