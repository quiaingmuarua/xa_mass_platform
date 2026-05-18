package com.xa.mass.engine.stage;

import com.xa.mass.engine.util.TraceEventLogger;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Owner-backed task stage evidence entry and read surface.
 *
 * <p>This service keeps stage evidence separate from public final result rows.
 * It owns only entry/read handoff and trace emission; stage projection truth
 * remains in {@link TaskStageEvidenceOwner}.</p>
 */
public final class TaskStageEvidenceService {

    private final TaskStageEvidenceOwner stageEvidenceOwner;
    private final TraceEventLogger traceEventLogger;

    public TaskStageEvidenceService(TaskStageEvidenceOwner stageEvidenceOwner,
                                    TraceEventLogger traceEventLogger) {
        this.stageEvidenceOwner = Objects.requireNonNull(stageEvidenceOwner, "stageEvidenceOwner");
        this.traceEventLogger = traceEventLogger != null ? traceEventLogger : TraceEventLogger.noop();
    }

    public TaskStageEvidenceResult applyEvidence(TaskStageEvidence evidence) {
        TaskStageEvidenceResult result = stageEvidenceOwner.applyEvidence(evidence);
        traceEventLogger.taskStageEvidenceApplied(result);
        return result;
    }

    public Optional<TaskStageProjection> projection(String taskId, String messageId, String stageName) {
        return stageEvidenceOwner.projection(taskId, messageId, stageName);
    }

    public List<TaskStageProjection> projectionsForMessage(String taskId, String messageId) {
        return stageEvidenceOwner.projectionsForMessage(taskId, messageId);
    }
}
