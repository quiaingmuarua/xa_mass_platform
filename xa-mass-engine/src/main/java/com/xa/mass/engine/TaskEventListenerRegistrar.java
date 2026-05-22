package com.xa.mass.engine;

import com.xa.mass.base.model.Task;

import java.util.function.Consumer;

/**
 * Narrow in-process event-listener registration surface for engine runtime
 * wiring.
 */
public interface TaskEventListenerRegistrar {

    void addTaskCreatedListener(Consumer<Task> listener);
    void removeTaskCreatedListener(Consumer<Task> listener);

    void addTaskAssignedListener(Consumer<Task> listener);
    void removeTaskAssignedListener(Consumer<Task> listener);

    void addTaskReadyListener(Consumer<Task> listener);
    void removeTaskReadyListener(Consumer<Task> listener);

    void addTaskDispatchListener(Consumer<Task> listener);
    void removeTaskDispatchListener(Consumer<Task> listener);

    void addTaskTerminalListener(Consumer<Task> listener);
    void removeTaskTerminalListener(Consumer<Task> listener);

    void addTaskWorkAttemptClosedListener(TaskWorkAttemptClosedListener listener);
    void removeTaskWorkAttemptClosedListener(TaskWorkAttemptClosedListener listener);

    void addTaskWorkLogicallyFinalListener(TaskWorkLogicallyFinalListener listener);
    void removeTaskWorkLogicallyFinalListener(TaskWorkLogicallyFinalListener listener);
}
