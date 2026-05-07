package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskStateValidationResult;

import java.util.List;

/**
 * Narrow task-query surface for bounded shell/debug inspection.
 */
public interface TaskQueryPort {

    Task getTask(String taskId);

    List<Task> listTasksPaged(int offset, int limit);

    List<Task> getTasksByStatus(TaskStatus status);

    /**
     * @deprecated compatibility residue read only; do not build new engine or
     * external module behavior on top of compatibility task-message snapshots.
     */
    @Deprecated
    @CompatibilityProjectionOnly
    TaskMessageSnapshotView getTaskMessageSnapshotView(String taskId, int limit);

    /**
     * @deprecated compatibility residue read only; runtime truth lives in task
     * aggregate plus {@code TaskWorkRuntime}, not in a stored projection row.
     */
    @Deprecated
    @CompatibilityProjectionOnly
    TaskMessageView getTaskMessageView(String taskId, String messageId);

    /**
     * @deprecated compatibility audit only; full attempt history belongs to
     * trace/audit infrastructure rather than engine hot-path contracts.
     */
    @Deprecated
    @CompatibilityProjectionOnly
    List<TaskMessageAttemptView> getTaskMessageAttemptAuditViews(String taskId, String messageId);

    /**
     * @deprecated compatibility audit only.
     */
    @Deprecated
    @CompatibilityProjectionOnly
    TaskMessageAttemptView getLatestTaskMessageAttemptView(String taskId, String messageId);

    /**
     * @deprecated transitional compatibility lookup only; active execution
     * ownership truth lives in runtime lease state.
     */
    @Deprecated
    @CompatibilityProjectionOnly
    TaskMessageAttemptView getLatestActiveTaskMessageAttemptView(String taskId, String messageId);

    TaskStateResolutionResult resolveTaskState(String taskId);

    TaskStateValidationResult validateTaskState(String taskId);
}
