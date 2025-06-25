package com.xa.mass.engine.listener;

import com.xa.mass.engine.model.task.Task;

public interface TaskCompletionListener {
    void onTaskCompleted(Task task);
    void onAllTasksCompleted();
} 