package com.xa.mass.transport.runtime.worker;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.listener.TaskMsgDispatchListener;
import com.xa.mass.engine.worker.WorkerAdapter;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.runtime.TransportRuntimeRegistry;
import com.xa.mass.transport.model.TaskDispatchItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Routes logical task dispatches to the transport adapter selected by each
 * worker's resolved adapter identity.
 */
public class TransportRoutingTaskMsgDispatchListener implements TaskMsgDispatchListener {

    private static final Logger logger = LoggerFactory.getLogger(TransportRoutingTaskMsgDispatchListener.class);

    private final WorkerManager workerManager;
    private final TransportRuntimeRegistry transportRuntimeRegistry;

    public TransportRoutingTaskMsgDispatchListener(WorkerManager workerManager,
                                                   TransportRuntimeRegistry transportRuntimeRegistry) {
        this.workerManager = Objects.requireNonNull(workerManager, "workerManager");
        this.transportRuntimeRegistry = Objects.requireNonNull(transportRuntimeRegistry, "transportRuntimeRegistry");
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
            List<DispatchOutcome> outcomes = entry.getKey().dispatchTaskItems(List.copyOf(entry.getValue()));
            logDispatchOutcomes(entry.getKey(), outcomes);
        }
    }

    private void logDispatchOutcomes(WorkerAdapter adapter, List<DispatchOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) {
            return;
        }
        for (DispatchOutcome outcome : outcomes) {
            if (outcome == null) {
                continue;
            }
            if (outcome.getStatus() == DispatchOutcomeStatus.SENT
                    || outcome.getStatus() == DispatchOutcomeStatus.QUEUED) {
                logger.debug("Transport dispatch outcome: adapterId={}, workerId={}, taskId={}, messageId={}, status={}",
                        outcome.getAdapterId(), outcome.getWorkerId(), outcome.getTaskId(), outcome.getMessageId(),
                        outcome.getStatus());
                continue;
            }
            logger.warn("Transport dispatch outcome: adapterId={}, workerId={}, taskId={}, messageId={}, status={}, retryable={}, reason={}, routedAdapter={}",
                    outcome.getAdapterId(), outcome.getWorkerId(), outcome.getTaskId(), outcome.getMessageId(),
                    outcome.getStatus(), outcome.isRetryable(), outcome.getReason(),
                    adapter != null ? adapter.adapterId() : null);
        }
    }

    private WorkerAdapter resolveAdapter(TaskMsg taskMsg) {
        String workerId = taskMsg != null ? taskMsg.getLatestAttemptWorkerId() : null;
        if (workerId == null || workerManager.getWorker(workerId) == null) {
            throw new IllegalStateException("Cannot dispatch task item because worker is missing: " + workerId);
        }
        return transportRuntimeRegistry.resolveDispatchAdapter(workerId);
    }
}
