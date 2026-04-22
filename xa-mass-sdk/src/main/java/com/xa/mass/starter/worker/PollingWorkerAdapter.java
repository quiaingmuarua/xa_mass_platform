package com.xa.mass.starter.worker;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.worker.WorkerAdapter;
import com.xa.mass.transport.channel.TaskDispatchChannel;
import com.xa.mass.transport.channel.TaskPullChannel;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.model.TaskDispatchItem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Pull-based worker adapter for crawlers, queue consumers, and other workers
 * that do not maintain a server-push transport.
 */
public class PollingWorkerAdapter implements WorkerAdapter, TaskDispatchChannel, TaskPullChannel, TaskResultIngestChannel {

    private static final Logger logger = LoggerFactory.getLogger(PollingWorkerAdapter.class);

    /** Maximum items held per worker inbox before new dispatches are dropped. */
    static final int MAX_INBOX_SIZE = 10_000;

    public static final String PROTOCOL = "polling";

    private final TaskManager taskManager;
    private final WorkerSystemEventChannel systemEventChannel;
    private final ConcurrentMap<String, Deque<TaskDispatchItem>> inboxByWorkerId = new ConcurrentHashMap<>();

    public PollingWorkerAdapter(TaskManager taskManager, WorkerSystemEventChannel systemEventChannel) {
        this.taskManager = Objects.requireNonNull(taskManager, "taskManager");
        this.systemEventChannel = Objects.requireNonNull(systemEventChannel, "systemEventChannel");
    }

    @Override
    public String protocol() {
        return PROTOCOL;
    }

    @Override
    public void onTaskMsgsReady(Task task, List<TaskMsg> taskMsgs) {
        dispatchTaskMessages(task, taskMsgs);
    }

    @Override
    public void dispatchTaskMessages(Task task, List<TaskMsg> taskMsgs) {
        if (task == null || taskMsgs == null || taskMsgs.isEmpty()) {
            return;
        }
        for (TaskMsg taskMsg : taskMsgs) {
            if (taskMsg == null || taskMsg.getLatestAttemptWorkerId() == null || taskMsg.getLatestAttemptWorkerId().isBlank()) {
                continue;
            }
            String workerId = taskMsg.getLatestAttemptWorkerId();
            Deque<TaskDispatchItem> inbox = inbox(workerId);
            synchronized (inbox) {
                if (inbox.size() >= MAX_INBOX_SIZE) {
                    logger.warn("Polling inbox for worker {} is full ({} items); dropping dispatch for msg {}",
                            workerId, inbox.size(), taskMsg.getMsgId());
                    continue;
                }
                inbox.addLast(toDispatchItem(task, taskMsg));
            }
        }
    }

    @Override
    public List<TaskDispatchItem> pollTaskMessages(String workerId, int maxMessages) {
        if (workerId == null || workerId.isBlank() || maxMessages <= 0) {
            return List.of();
        }
        Deque<TaskDispatchItem> inbox = inboxByWorkerId.get(workerId);
        if (inbox == null) {
            return List.of();
        }
        List<TaskDispatchItem> polled = new ArrayList<>(Math.max(1, maxMessages));
        synchronized (inbox) {
            while (polled.size() < maxMessages) {
                TaskDispatchItem item = inbox.pollFirst();
                if (item == null) {
                    break;
                }
                polled.add(item);
            }
            // Remove empty inboxes to prevent memory accumulation from offline workers.
            if (inbox.isEmpty()) {
                inboxByWorkerId.remove(workerId, inbox);
            }
        }
        return List.copyOf(polled);
    }

    @Override
    public boolean ingestTaskResult(String taskId,
                                    String msgId,
                                    boolean success,
                                    String detail,
                                    String errorCode,
                                    Map<String, Object> output) {
        return taskManager.handleTaskMessageResult(taskId, msgId, success, detail, errorCode, output);
    }

    public void announceWorkerOnline(String workerId, String reason) {
        systemEventChannel.publishWorkerOnline(workerId, reason, workerId);
    }

    public void announceWorkerOffline(String workerId, String reason) {
        systemEventChannel.publishWorkerOffline(workerId, reason, workerId);
    }

    public void publishWorkerHeartbeat(String workerId, String reason) {
        systemEventChannel.publishWorkerHeartbeat(workerId, reason, workerId);
    }

    private Deque<TaskDispatchItem> inbox(String workerId) {
        return inboxByWorkerId.computeIfAbsent(workerId, ignored -> new ArrayDeque<>());
    }

    private TaskDispatchItem toDispatchItem(Task task, TaskMsg taskMsg) {
        return new TaskDispatchItem(
                task.getTid(),
                taskMsg.getMsgId(),
                task.getTaskName(),
                task.getProject(),
                task.getUser() != null ? task.getUser().getUserId() : null,
                taskMsg.getRetryCount(),
                taskMsg.getLatestAttemptWorkerId(),
                taskMsg.getLatestAttemptWorkerContextId(),
                taskMsg.getLatestAttemptBatchId(),
                taskMsg.getInput(),
                task.getSharedConfig()
        );
    }
}
