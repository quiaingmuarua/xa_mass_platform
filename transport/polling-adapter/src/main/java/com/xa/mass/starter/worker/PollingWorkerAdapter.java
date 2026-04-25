package com.xa.mass.starter.worker;

import com.xa.mass.engine.worker.WorkerAdapter;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.channel.TaskPullChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.model.TaskDispatchItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Pull-based worker adapter for crawlers, queue consumers, and other workers
 * that do not maintain a server-push transport.
 */
public class PollingWorkerAdapter implements WorkerAdapter, TaskPullChannel {

    private static final Logger logger = LoggerFactory.getLogger(PollingWorkerAdapter.class);

    /** Maximum items held per worker inbox before new dispatches are dropped. */
    static final int MAX_INBOX_SIZE = 10_000;

    public static final String PROTOCOL = "polling";

    private final WorkerSystemEventChannel systemEventChannel;
    private final ConcurrentMap<String, Deque<TaskDispatchItem>> inboxByWorkerId = new ConcurrentHashMap<>();

    public PollingWorkerAdapter(WorkerSystemEventChannel systemEventChannel) {
        this.systemEventChannel = Objects.requireNonNull(systemEventChannel, "systemEventChannel");
    }

    @Override
    public String protocol() {
        return PROTOCOL;
    }

    @Override
    public Set<String> aliases() {
        return Set.of("pull", "queue", WorkerTransportHints.POLLING);
    }

    @Override
    public void dispatchTaskItems(List<TaskDispatchItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (TaskDispatchItem item : items) {
            if (item == null || item.getWorkerId() == null || item.getWorkerId().isBlank()) {
                continue;
            }
            String workerId = item.getWorkerId();
            Deque<TaskDispatchItem> inbox = inbox(workerId);
            synchronized (inbox) {
                if (inbox.size() >= MAX_INBOX_SIZE) {
                    logger.warn("Polling inbox for worker {} is full ({} items); dropping dispatch for messageId {}",
                            workerId, inbox.size(), item.getMessageId());
                    continue;
                }
                inbox.addLast(item);
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
}
