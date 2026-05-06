package com.xa.mass.base.runtime.dispatch;

import com.xa.mass.base.model.Task;

import java.util.List;
import java.util.Objects;

/**
 * Producer-side adapter that turns direct dispatch-ready callbacks into
 * explicit handoff submissions.
 */
public final class TaskDispatchHandoffListener implements TaskMsgDispatchListener {

    private final TaskDispatchHandoff handoff;

    public TaskDispatchHandoffListener(TaskDispatchHandoff handoff) {
        this.handoff = Objects.requireNonNull(handoff, "handoff");
    }

    @Override
    public void onTaskMsgsReady(Task task, List<TaskDispatchBinding> dispatchBindings) {
        if (task == null || dispatchBindings == null || dispatchBindings.isEmpty()) {
            return;
        }
        handoff.submit(new TaskDispatchBatch(TaskDispatchContext.from(task), dispatchBindings));
    }
}
