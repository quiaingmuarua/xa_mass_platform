package com.xa.mass.engine.listener;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.engine.model.MatchedWorkerContext;

import java.util.List;

/**
 * 消息分配监听器接口
 */
public interface TaskMsgAssignListener {
    List<TaskDispatchBinding> onMsgAssign(Task task, List<MatchedWorkerContext> matchedWorkers);
}
