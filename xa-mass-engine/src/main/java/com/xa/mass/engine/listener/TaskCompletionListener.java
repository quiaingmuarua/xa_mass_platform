package com.xa.mass.engine.listener;

import com.xa.mass.eventbus.model.Task;

public interface TaskCompletionListener {
    void onTaskCompleted(Task task);
    void onAllTasksCompleted();
} 