package com.xa.mass.starter;

import com.xa.mass.base.channel.eventbus.core.EventBusFacade;
import com.xa.mass.base.channel.eventbus.core.EventBusFactory;
import com.xa.mass.base.channel.eventbus.event.task.TaskAssignedEvent;
import com.xa.mass.base.channel.eventbus.event.task.TaskCreatedEvent;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.TaskEventListenerRegistrar;
import com.xa.mass.engine.worker.WorkerStatusEventListener;
import com.xa.mass.engine.listener.EventListenerRegistry;
import com.xa.mass.runtime.worker.WorkerResourceRuntime;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Optional process-local bridge from engine in-process events to the legacy
 * runtime event bus.
 *
 * <p>This is shell wiring only. It must not be confused with a distributed
 * dispatch or lifecycle propagation contract.
 */
public final class RuntimeEventBusEngineBridge implements EngineRuntimeBridge {

    private final EventBusFacade<?> eventBus;
    private Consumer<Task> taskCreatedListener;
    private Consumer<Task> taskAssignedListener;
    private WorkerStatusEventListener workerStatusEventListener;
    private TaskEventListenerRegistrar registeredEventListeners;

    private RuntimeEventBusEngineBridge(EventBusFacade<?> eventBus) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    }

    public static RuntimeEventBusEngineBridge runtimeBus() {
        return new RuntimeEventBusEngineBridge(EventBusFactory.get("runtime"));
    }

    @Override
    public void start(TaskEventListenerRegistrar eventListeners, WorkerResourceRuntime workerResourceRuntime) {
        start(eventListeners, workerResourceRuntime, null);
    }

    @Override
    public void start(TaskEventListenerRegistrar eventListeners,
                      WorkerResourceRuntime workerResourceRuntime,
                      Runnable dispatchWakeupCallback) {
        stop();
        @SuppressWarnings("unchecked")
        EventBusFacade<Object> bus = (EventBusFacade<Object>) eventBus;
        this.registeredEventListeners = Objects.requireNonNull(eventListeners, "eventListeners");
        this.taskCreatedListener = task -> bus.post(new TaskCreatedEvent(task, null, null));
        this.taskAssignedListener = task -> bus.post(new TaskAssignedEvent(task, null, null));
        registeredEventListeners.addTaskCreatedListener(taskCreatedListener);
        registeredEventListeners.addTaskAssignedListener(taskAssignedListener);
        this.workerStatusEventListener =
                EventListenerRegistry.registerWorkerStatusListeners(
                        eventBus,
                        workerResourceRuntime,
                        dispatchWakeupCallback);
    }

    @Override
    public void stop() {
        if (registeredEventListeners != null) {
            if (taskCreatedListener != null) {
                registeredEventListeners.removeTaskCreatedListener(taskCreatedListener);
            }
            if (taskAssignedListener != null) {
                registeredEventListeners.removeTaskAssignedListener(taskAssignedListener);
            }
        }
        if (workerStatusEventListener != null) {
            try {
                eventBus.unregister(workerStatusEventListener);
            } catch (RuntimeException ignored) {
                // Best-effort cleanup for legacy shell bridge listeners.
            }
        }
        taskCreatedListener = null;
        taskAssignedListener = null;
        workerStatusEventListener = null;
        registeredEventListeners = null;
    }
}
