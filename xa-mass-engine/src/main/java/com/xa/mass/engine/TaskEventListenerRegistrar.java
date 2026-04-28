package com.xa.mass.engine;

import com.xa.mass.base.model.Task;

import java.util.function.Consumer;

/**
 * Narrow in-process event-listener registration surface for engine runtime
 * wiring.
 */
public interface TaskEventListenerRegistrar {

    void addTaskCreatedListener(Consumer<Task> listener);

    void addTaskAssignedListener(Consumer<Task> listener);

    void addTaskReadyListener(Consumer<Task> listener);

    void addTaskDispatchListener(Consumer<Task> listener);

    void addTaskTerminalListener(Consumer<Task> listener);

    void addTaskMessageAttemptClosedListener(TaskMessageAttemptClosedListener listener);

    void addTaskMessageLogicallyFinalListener(TaskMessageLogicallyFinalListener listener);
}
