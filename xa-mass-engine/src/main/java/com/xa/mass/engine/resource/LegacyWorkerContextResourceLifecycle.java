package com.xa.mass.engine.resource;

import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.util.TraceEventLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the transitional WorkerContext resource lifecycle while WorkerContext is
 * still a runtime binding payload.
 */
public class LegacyWorkerContextResourceLifecycle {
    private static final Logger log = LoggerFactory.getLogger(LegacyWorkerContextResourceLifecycle.class);

    private final WorkerManager workerManager;
    private final TraceEventLogger traceEventLogger;

    public LegacyWorkerContextResourceLifecycle(WorkerManager workerManager, TraceEventLogger traceEventLogger) {
        this.workerManager = workerManager;
        this.traceEventLogger = traceEventLogger == null ? TraceEventLogger.noop() : traceEventLogger;
    }

    public boolean prepareForDispatch(Task task, WorkerContext workerContext, String source) {
        if (workerContext == null) {
            return true;
        }
        if (task == null || task.getTid() == null || task.getTid().isBlank()) {
            return false;
        }

        boolean changed = false;
        String taskId = task.getTid();
        if (workerContext.getStatus() == WorkerContextStatus.IDLE) {
            WorkerContextStatus fromStatus = workerContext.getStatus();
            if (!workerContext.bindToTask(taskId)) {
                return false;
            }
            traceEventLogger.workerContextStatusTransition(
                    taskId,
                    workerContext,
                    fromStatus,
                    workerContext.getStatus(),
                    "PREPARE_FOR_DISPATCH",
                    source,
                    "workerContext reserved for task"
            );
            changed = true;
        }
        if (workerContext.getStatus() == WorkerContextStatus.RESERVED
                && taskId.equals(workerContext.getLastBindTaskId())) {
            WorkerContextStatus fromStatus = workerContext.getStatus();
            if (!workerContext.startOccupying()) {
                return false;
            }
            traceEventLogger.workerContextStatusTransition(
                    taskId,
                    workerContext,
                    fromStatus,
                    workerContext.getStatus(),
                    "PREPARE_FOR_DISPATCH",
                    source,
                    "workerContext advanced to occupied"
            );
            changed = true;
        }

        boolean alreadySendingForTask = workerContext.getStatus() == WorkerContextStatus.OCCUPIED
                && taskId.equals(workerContext.getLastBindTaskId());
        if (!alreadySendingForTask && workerContext.getStatus() != WorkerContextStatus.OCCUPIED) {
            return false;
        }

        return !changed || workerManager.updateWorkerContextById(workerContext.getWorkerContextId(), workerContext);
    }

    public void releaseIfOwnedByTask(Task task,
                                     WorkerContext workerContext,
                                     String trigger,
                                     String source,
                                     String reason) {
        if (task == null || workerContext == null) {
            return;
        }
        releaseLoadedContext(task.getTid(), workerContext.getWorkerId(), workerContext, trigger, source, reason, false);
    }

    public void releaseIfOwnedByTask(String taskId,
                                     String workerId,
                                     String workerContextId,
                                     String trigger,
                                     String source,
                                     String reason,
                                     boolean emitResourceResultEvents) {
        if (workerContextId == null || workerContextId.isBlank()) {
            return;
        }

        WorkerContext workerContext = workerManager.getWorkerContextById(workerContextId);
        if (workerContext == null || !workerId.equals(workerContext.getWorkerId())) {
            return;
        }
        releaseLoadedContext(taskId, workerId, workerContext, trigger, source, reason, emitResourceResultEvents);
    }

    private void releaseLoadedContext(String taskId,
                                      String workerId,
                                      WorkerContext workerContext,
                                      String trigger,
                                      String source,
                                      String reason,
                                      boolean emitResourceResultEvents) {
        if (taskId == null || taskId.isBlank() || workerContext == null) {
            return;
        }
        if (workerContext.getLastBindTaskId() != null && !taskId.equals(workerContext.getLastBindTaskId())) {
            return;
        }
        if (workerContext.getStatus() == WorkerContextStatus.IDLE) {
            return;
        }
        WorkerContextStatus fromStatus = workerContext.getStatus();
        if (workerContext.release()) {
            boolean stored = workerManager.updateWorkerContextById(workerContext.getWorkerContextId(), workerContext);
            if (!stored) {
                log.warn("WorkerContext {} release for task {} was not persisted ({})",
                        workerContext.getWorkerContextId(), taskId, reason);
            }
            traceEventLogger.workerContextStatusTransition(
                    taskId,
                    workerContext,
                    fromStatus,
                    workerContext.getStatus(),
                    trigger,
                    source,
                    reason
            );
            if (emitResourceResultEvents) {
                traceEventLogger.resourceReleased(taskId, workerId, workerContext.getWorkerContextId(),
                        "workerContext released");
            }
            return;
        }

        if (emitResourceResultEvents) {
            traceEventLogger.resourceReleaseFailed(taskId, workerId, workerContext.getWorkerContextId(),
                    "workerContext could not transition to IDLE from " + workerContext.getStatus());
        }
        log.warn("WorkerContext {} on worker {} could not be released from status {} for task {}",
                workerContext.getWorkerContextId(), workerId, workerContext.getStatus(), taskId);
    }
}
