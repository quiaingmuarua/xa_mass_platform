package com.xa.mass.engine;

import com.xa.mass.base.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Owns in-process task lifecycle event listeners and their invocation order.
 *
 * <p><b>Two-tier event model:</b> This publisher owns the synchronous, in-process
 * listener chain (used by the engine internals). An external runtime EventBus layer
 * (wired in {@code MassEngine}) bridges selected events to out-of-process subscribers.
 * Add listeners here when the reaction must happen inline; subscribe to the EventBus
 * when loose coupling or async delivery is required.
 */
public class TaskEventPublisher implements TaskAssignmentEventSink, TaskEventListenerRegistrar {

    private static final Logger logger = LoggerFactory.getLogger(TaskEventPublisher.class);

    private final List<Consumer<Task>> taskCreatedListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Task>> taskAssignedListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Task>> taskReadyListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Task>> taskDispatchListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Task>> taskTerminalListeners = new CopyOnWriteArrayList<>();
    private final List<TaskWorkAttemptClosedListener> taskWorkAttemptClosedListeners = new CopyOnWriteArrayList<>();
    private final List<TaskWorkLogicallyFinalListener> taskWorkLogicallyFinalListeners = new CopyOnWriteArrayList<>();

    public void addTaskCreatedListener(Consumer<Task> listener) {
        if (listener != null) {
            taskCreatedListeners.add(listener);
        }
    }

    public void removeTaskCreatedListener(Consumer<Task> listener) {
        if (listener != null) {
            taskCreatedListeners.remove(listener);
        }
    }

    public void addTaskAssignedListener(Consumer<Task> listener) {
        if (listener != null) {
            taskAssignedListeners.add(listener);
        }
    }

    public void removeTaskAssignedListener(Consumer<Task> listener) {
        if (listener != null) {
            taskAssignedListeners.remove(listener);
        }
    }

    public void addTaskReadyListener(Consumer<Task> listener) {
        if (listener != null) {
            taskReadyListeners.add(listener);
        }
    }

    public void removeTaskReadyListener(Consumer<Task> listener) {
        if (listener != null) {
            taskReadyListeners.remove(listener);
        }
    }

    public void addTaskDispatchListener(Consumer<Task> listener) {
        if (listener != null) {
            taskDispatchListeners.add(listener);
        }
    }

    public void removeTaskDispatchListener(Consumer<Task> listener) {
        if (listener != null) {
            taskDispatchListeners.remove(listener);
        }
    }

    public void addTaskTerminalListener(Consumer<Task> listener) {
        if (listener != null) {
            taskTerminalListeners.add(listener);
        }
    }

    public void removeTaskTerminalListener(Consumer<Task> listener) {
        if (listener != null) {
            taskTerminalListeners.remove(listener);
        }
    }

    public void addTaskWorkAttemptClosedListener(TaskWorkAttemptClosedListener listener) {
        if (listener != null) {
            taskWorkAttemptClosedListeners.add(listener);
        }
    }

    public void removeTaskWorkAttemptClosedListener(TaskWorkAttemptClosedListener listener) {
        if (listener != null) {
            taskWorkAttemptClosedListeners.remove(listener);
        }
    }

    public void addTaskWorkLogicallyFinalListener(TaskWorkLogicallyFinalListener listener) {
        if (listener != null) {
            taskWorkLogicallyFinalListeners.add(listener);
        }
    }

    public void removeTaskWorkLogicallyFinalListener(TaskWorkLogicallyFinalListener listener) {
        if (listener != null) {
            taskWorkLogicallyFinalListeners.remove(listener);
        }
    }

    public void publishTaskCreated(Task task) {
        for (Consumer<Task> listener : taskCreatedListeners) {
            try {
                listener.accept(task);
            } catch (Exception e) {
                logger.error("CREATED listener execution failed for task {}", task.getTid(), e);
            }
        }
    }

    public void publishTaskAssigned(Task task) {
        for (Consumer<Task> listener : taskAssignedListeners) {
            try {
                listener.accept(task);
            } catch (Exception e) {
                logger.error("ASSIGNED listener execution failed for task {}", task.getTid(), e);
            }
        }
    }

    public void publishTaskReady(Task task) {
        for (Consumer<Task> listener : taskReadyListeners) {
            try {
                listener.accept(task);
            } catch (Exception e) {
                logger.error("READY listener execution failed for task {}", task.getTid(), e);
            }
        }
    }

    public void publishTaskDispatchRequested(Task task) {
        for (Consumer<Task> listener : taskDispatchListeners) {
            try {
                listener.accept(task);
            } catch (Exception e) {
                logger.error("Dispatch listener execution failed for task {}", task.getTid(), e);
            }
        }
    }

    public void publishTaskTerminal(Task task) {
        for (Consumer<Task> listener : taskTerminalListeners) {
            try {
                listener.accept(task);
            } catch (Exception e) {
                logger.error("TERMINAL listener execution failed for task {}", task.getTid(), e);
            }
        }
    }

    public void publishTaskWorkAttemptClosed(Task task, TaskWorkAttemptClosedEvent event) {
        for (TaskWorkAttemptClosedListener listener : taskWorkAttemptClosedListeners) {
            try {
                listener.onTaskWorkAttemptClosed(task, event);
            } catch (Exception e) {
                logger.error("Task work attempt-closed listener failed for task {}, msg {}, attempt {}",
                        task.getTid(),
                        event != null ? event.messageId() : "null",
                        event != null ? event.attemptId() : "null",
                        e);
            }
        }
    }

    public void publishTaskWorkLogicallyFinal(Task task, TaskWorkLogicallyFinalEvent event) {
        for (TaskWorkLogicallyFinalListener listener : taskWorkLogicallyFinalListeners) {
            try {
                listener.onTaskWorkLogicallyFinal(task, event);
            } catch (Exception e) {
                logger.error("Task work logically-final listener failed for task {}, msg {}",
                        task.getTid(), event != null ? event.messageId() : "null", e);
            }
        }
    }
}
