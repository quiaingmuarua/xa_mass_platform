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
    public void addTaskAssignedListener(Consumer<Task> listener) {
        registrar.addTaskAssignedListener(listener);
    }

    @Override
    public void addTaskReadyListener(Consumer<Task> listener) {
        registrar.addTaskReadyListener(listener);
    }

    @Override
    public void addTaskDispatchListener(Consumer<Task> listener) {
        registrar.addTaskDispatchListener(listener);
    }

    @Override
    public void addTaskTerminalListener(Consumer<Task> listener) {
        registrar.addTaskTerminalListener(listener);
    }

    @Override
    public void addTaskMessageAttemptClosedListener(TaskMessageAttemptClosedListener listener) {
        registrar.addTaskMessageAttemptClosedListener(listener);
    }

    @Override
    public void addTaskMessageLogicallyFinalListener(TaskMessageLogicallyFinalListener listener) {
        registrar.addTaskMessageLogicallyFinalListener(listener);
    }

    @Override
    public void publishTaskAssigned(Task task) {
        assignmentEventSink.publishTaskAssigned(task);
    }
}
