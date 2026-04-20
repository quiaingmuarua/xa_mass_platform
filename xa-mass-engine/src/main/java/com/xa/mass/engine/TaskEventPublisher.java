package com.xa.mass.engine;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Owns in-process task lifecycle event listeners and their invocation order.
 */
class TaskEventPublisher {

    private static final Logger logger = LoggerFactory.getLogger(TaskEventPublisher.class);

    private final List<Consumer<Task>> taskReadyListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Task>> taskDispatchListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Task>> taskTerminalListeners = new CopyOnWriteArrayList<>();
    private final List<TaskMessageAttemptClosedListener> taskMessageAttemptClosedListeners = new CopyOnWriteArrayList<>();
    private final List<TaskMessageLogicallyFinalListener> taskMessageLogicallyFinalListeners = new CopyOnWriteArrayList<>();

    void addTaskReadyListener(Consumer<Task> listener) {
        if (listener != null) {
            taskReadyListeners.add(listener);
        }
    }

    void addTaskDispatchListener(Consumer<Task> listener) {
        if (listener != null) {
            taskDispatchListeners.add(listener);
        }
    }

    void addTaskTerminalListener(Consumer<Task> listener) {
        if (listener != null) {
            taskTerminalListeners.add(listener);
        }
    }

    void addTaskMessageAttemptClosedListener(TaskMessageAttemptClosedListener listener) {
        if (listener != null) {
            taskMessageAttemptClosedListeners.add(listener);
        }
    }

    void addTaskMessageLogicallyFinalListener(TaskMessageLogicallyFinalListener listener) {
        if (listener != null) {
            taskMessageLogicallyFinalListeners.add(listener);
        }
    }

    void publishTaskReady(Task task) {
        for (Consumer<Task> listener : taskReadyListeners) {
            try {
                listener.accept(task);
            } catch (Exception e) {
                logger.error("READY listener execution failed for task {}", task.getTid(), e);
            }
        }
    }

    void publishTaskDispatchRequested(Task task) {
        for (Consumer<Task> listener : taskDispatchListeners) {
            try {
                listener.accept(task);
            } catch (Exception e) {
                logger.error("Dispatch listener execution failed for task {}", task.getTid(), e);
            }
        }
    }

    void publishTaskTerminal(Task task) {
        for (Consumer<Task> listener : taskTerminalListeners) {
            try {
                listener.accept(task);
            } catch (Exception e) {
                logger.error("TERMINAL listener execution failed for task {}", task.getTid(), e);
            }
        }
    }

    void publishTaskMessageAttemptClosed(Task task, TaskMsg taskMsg, TaskMsgAttempt attempt) {
        for (TaskMessageAttemptClosedListener listener : taskMessageAttemptClosedListeners) {
            try {
                listener.onTaskMessageAttemptClosed(task, taskMsg, attempt);
            } catch (Exception e) {
                logger.error("Task message attempt-closed listener failed for task {}, msg {}, attempt {}",
                        task.getTid(), taskMsg.getMsgId(), attempt != null ? attempt.getAttemptId() : "null", e);
            }
        }
    }

    void publishTaskMessageLogicallyFinal(Task task, TaskMsg taskMsg) {
        for (TaskMessageLogicallyFinalListener listener : taskMessageLogicallyFinalListeners) {
            try {
                listener.onTaskMessageLogicallyFinal(task, taskMsg);
            } catch (Exception e) {
                logger.error("Task message logically-final listener failed for task {}, msg {}",
                        task.getTid(), taskMsg.getMsgId(), e);
            }
        }
    }
}
