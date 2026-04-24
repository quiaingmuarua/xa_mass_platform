package com.xa.mass.starter.worker;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.Worker;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.listener.TaskMsgDispatchListener;
import com.xa.mass.engine.worker.WorkerAdapter;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.TaskDispatchItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Routes logical task dispatches to the transport adapter selected by each
 * worker's declared online strategy.
 */
public class TransportRoutingTaskMsgDispatchListener implements TaskMsgDispatchListener {

    private static final Logger logger = LoggerFactory.getLogger(TransportRoutingTaskMsgDispatchListener.class);

    private final WorkerManager workerManager;
    private final Map<String, WorkerAdapter> adaptersByTransportHint;

    public TransportRoutingTaskMsgDispatchListener(WorkerManager workerManager,
                                                   List<? extends WorkerAdapter> adapters) {
        this.workerManager = Objects.requireNonNull(workerManager, "workerManager");
        Objects.requireNonNull(adapters, "adapters");
        if (adapters.isEmpty()) {
            throw new IllegalArgumentException("At least one worker adapter is required");
        }
        this.adaptersByTransportHint = new LinkedHashMap<>();
        for (WorkerAdapter adapter : adapters) {
            if (adapter == null) {
                continue;
            }
            registerAdapter(adapter.transportHint(), adapter);
            for (String alias : adapter.aliases()) {
                registerAdapter(alias, adapter);
            }
        }
        if (adaptersByTransportHint.isEmpty()) {
            throw new IllegalArgumentException("At least one non-null worker adapter is required");
        }
    }

    @Override
    public void onTaskMsgsReady(Task task, List<TaskMsg> taskMsgs) {
        if (task == null || taskMsgs == null || taskMsgs.isEmpty()) {
            return;
        }

        Map<WorkerAdapter, List<TaskDispatchItem>> grouped = new LinkedHashMap<>();
        for (TaskMsg taskMsg : taskMsgs) {
            WorkerAdapter adapter = resolveAdapter(taskMsg);
            grouped.computeIfAbsent(adapter, ignored -> new ArrayList<>()).add(TaskDispatchItem.from(task, taskMsg));
        }

        for (Map.Entry<WorkerAdapter, List<TaskDispatchItem>> entry : grouped.entrySet()) {
            entry.getKey().dispatchTaskItems(List.copyOf(entry.getValue()));
        }
    }

    private void registerAdapter(String protocol, WorkerAdapter adapter) {
        String normalized = normalizeProtocol(protocol);
        if (normalized == null) {
            return;
        }
        adaptersByTransportHint.put(normalized, adapter);
    }

    private WorkerAdapter resolveAdapter(TaskMsg taskMsg) {
        String workerId = taskMsg != null ? taskMsg.getLatestAttemptWorkerId() : null;
        Worker worker = workerId != null ? workerManager.getWorker(workerId) : null;
        if (worker == null) {
            throw new IllegalStateException("Cannot dispatch task item because worker is missing: " + workerId);
        }
        String requestedProtocol = normalizeProtocol(worker != null ? worker.getOnlineStrategy() : null);
        if (requestedProtocol == null) {
            throw new IllegalStateException("Worker transportHint/onlineStrategy must be set before dispatch: " + workerId);
        }
        WorkerAdapter adapter = adaptersByTransportHint.get(requestedProtocol);
        if (adapter != null) {
            return adapter;
        }
        List<String> availableProtocols = new ArrayList<>(new LinkedHashSet<>(adaptersByTransportHint.keySet()));
        Collections.sort(availableProtocols);
        logger.error("Worker {} requested unsupported transport '{}'; available transports={}",
                workerId, requestedProtocol, availableProtocols);
        throw new IllegalStateException("Unsupported worker transport '" + requestedProtocol
                + "' for worker " + workerId + "; available transports=" + availableProtocols);
    }

    private static String normalizeProtocol(String protocol) {
        return WorkerTransportHints.normalize(protocol);
    }
}
