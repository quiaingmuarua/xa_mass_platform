package com.xa.mass.engine;

import com.xa.mass.base.model.Task;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Public in-process event-listener registration surface for shell/testing
 * wiring. This keeps listener registration off the broader task command facade.
 */
public class TaskEventService implements TaskEventListenerRegistrar, TaskAssignmentEventSink {

    private final TaskEventListenerRegistrar registrar;
    private final TaskAssignmentEventSink assignmentEventSink;

    public TaskEventService(TaskManager taskManager) {
        this(Objects.requireNonNull(taskManager, "taskManager").events());
    }

    TaskEventService(TaskEventPublisher eventPublisher) {
        TaskEventPublisher publisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.registrar = publisher;
        this.assignmentEventSink = publisher;
    }

    @Override
    public void addTaskCreatedListener(Consumer<Task> listener) {
        registrar.addTaskCreatedListener(listener);
    }

    @Override
    public void removeTaskCreatedListener(Consumer<Task> listener) {
        registrar.removeTaskCreatedListener(listener);
    }

    @Override
    public void addTaskAssignedListener(Consumer<Task> listener) {
        registrar.addTaskAssignedListener(listener);
    }

    @Override
    public void removeTaskAssignedListener(Consumer<Task> listener) {
        registrar.removeTaskAssignedListener(listener);
    }

    @Override
    public void addTaskReadyListener(Consumer<Task> listener) {
        registrar.addTaskReadyListener(listener);
    }

    @Override
    public void removeTaskReadyListener(Consumer<Task> listener) {
        registrar.removeTaskReadyListener(listener);
    }

    @Override
    public void addTaskDispatchListener(Consumer<Task> listener) {
        registrar.addTaskDispatchListener(listener);
    }

    @Override
    public void removeTaskDispatchListener(Consumer<Task> listener) {
        registrar.removeTaskDispatchListener(listener);
    }

    @Override
    public void addTaskTerminalListener(Consumer<Task> listener) {
        registrar.addTaskTerminalListener(listener);
    }

    @Override
    public void removeTaskTerminalListener(Consumer<Task> listener) {
        registrar.removeTaskTerminalListener(listener);
    }

    @Override
    public void addTaskWorkAttemptClosedListener(TaskWorkAttemptClosedListener listener) {
        registrar.addTaskWorkAttemptClosedListener(listener);
    }

    @Override
    public void removeTaskWorkAttemptClosedListener(TaskWorkAttemptClosedListener listener) {
        registrar.removeTaskWorkAttemptClosedListener(listener);
    }

    @Override
    public void addTaskWorkLogicallyFinalListener(TaskWorkLogicallyFinalListener listener) {
        registrar.addTaskWorkLogicallyFinalListener(listener);
    }

    @Override
    public void removeTaskWorkLogicallyFinalListener(TaskWorkLogicallyFinalListener listener) {
        registrar.removeTaskWorkLogicallyFinalListener(listener);
    }

    @Override
    public void publishTaskAssigned(Task task) {
        assignmentEventSink.publishTaskAssigned(task);
    }
}
