package com.xa.mass.base.runtime.dispatch;

import com.xa.mass.base.model.Task;

import java.util.List;

@FunctionalInterface
public interface TaskMsgDispatchListener {
    void onTaskMsgsReady(Task task, List<TaskDispatchBinding> dispatchBindings);
}
