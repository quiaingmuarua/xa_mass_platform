package com.xa.mass.mock.starter;

import com.xa.mass.engine.model.task.Task;

public interface TaskCompletionListener {
    void onTaskCompleted(Task task);
    void onAllTasksCompleted();
} 