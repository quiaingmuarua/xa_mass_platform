package com.xa.mass.starter.worker;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.Worker;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.listener.TaskMsgDispatchListener;
import com.xa.mass.engine.worker.WorkerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Routes logical task dispatches to the transport adapter selected by each
 * worker's declared online strategy.
 */
public class TransportRoutingTaskMsgDispatchListener implements TaskMsgDispatchListener {

    private static final Logger logger = LoggerFactory.getLogger(TransportRoutingTaskMsgDispatchListener.class);

    private final WorkerManager workerManager;
    private final Map<String, WorkerAdapter> adaptersByProtocol;
    private final String defaultProtocol;

    public TransportRoutingTaskMsgDispatchListener(WorkerManager workerManager,
                                                   List<? extends WorkerAdapter> adapters,
                                                   String defaultProtocol) {
        this.workerManager = Objects.requireNonNull(workerManager, "workerManager");
        Objects.requireNonNull(adapters, "adapters");
        if (adapters.isEmpty()) {
            throw new IllegalArgumentException("At least one worker adapter is required");
        }
        this.adaptersByProtocol = new LinkedHashMap<>();
        for (WorkerAdapter adapter : adapters) {
            if (adapter == null) {
                continue;
            }
            adaptersByProtocol.put(normalizeProtocol(adapter.protocol()), adapter);
        }
        if (adaptersByProtocol.isEmpty()) {
            throw new IllegalArgumentException("At least one non-null worker adapter is required");
        }
        this.defaultProtocol = normalizeProtocol(
                defaultProtocol != null ? defaultProtocol : adaptersByProtocol.keySet().iterator().next()
        );
    }

    @Override
    public void onTaskMsgsReady(Task task, List<TaskMsg> taskMsgs) {
        if (task == null || taskMsgs == null || taskMsgs.isEmpty()) {
            return;
        }

        Map<WorkerAdapter, List<TaskMsg>> grouped = new LinkedHashMap<>();
        for (TaskMsg taskMsg : taskMsgs) {
            WorkerAdapter adapter = resolveAdapter(taskMsg);
            grouped.computeIfAbsent(adapter, ignored -> new ArrayList<>()).add(taskMsg);
        }

        for (Map.Entry<WorkerAdapter, List<TaskMsg>> entry : grouped.entrySet()) {
            entry.getKey().onTaskMsgsReady(task, List.copyOf(entry.getValue()));
        }
    }

    private WorkerAdapter resolveAdapter(TaskMsg taskMsg) {
        String workerId = taskMsg != null ? taskMsg.getLatestAttemptWorkerId() : null;
        Worker worker = workerId != null ? workerManager.getWorker(workerId) : null;
        String requestedProtocol = normalizeProtocol(worker != null ? worker.getOnlineStrategy() : null);
        WorkerAdapter adapter = adaptersByProtocol.get(requestedProtocol);
        if (adapter != null) {
            return adapter;
        }

        WorkerAdapter fallback = adaptersByProtocol.get(defaultProtocol);
        if (fallback != null) {
            if (requestedProtocol != null && !requestedProtocol.equals(defaultProtocol)) {
                logger.warn("Worker {} requested unsupported transport '{}'; falling back to '{}'",
                        workerId, requestedProtocol, defaultProtocol);
            }
            return fallback;
        }

        return adaptersByProtocol.values().iterator().next();
    }

    private static String normalizeProtocol(String protocol) {
        if (protocol == null || protocol.isBlank()) {
            return WebSocketWorkerAdapter.PROTOCOL;
        }
        String normalized = protocol.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "ws" -> WebSocketWorkerAdapter.PROTOCOL;
            case "pull", "queue" -> PollingWorkerAdapter.PROTOCOL;
            default -> normalized;
        };
    }
}
