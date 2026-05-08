package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;
import com.xa.mass.engine.model.TaskStateValidationResult;

import java.util.Objects;
import java.util.function.Function;

/**
 * Explicit compatibility query facade for bounded TaskMsg / TaskMsgAttempt
 * residue reads.
 *
 * <p>This service is intentionally named as compatibility residue so callers
 * do not mistake TaskMsg projection reads for the engine's default runtime
 * query model. Outer modules should assemble their own DTOs from the visitor
 * callbacks instead of depending on engine-owned compatibility view models.</p>
 */
@CompatibilityProjectionOnly
public class TaskCompatibilityQueryService {

    private final TaskCompatibilityProjectionAccess compatibilityQueries;
    private final Function<String, TaskStateValidationResult> projectionAudit;

    public TaskCompatibilityQueryService(TaskManager taskManager) {
        this(taskManager.compatibilityProjectionAccess(), taskManager::auditTaskProjectionState);
    }

    TaskCompatibilityQueryService(TaskCompatibilityProjectionAccess compatibilityQueries) {
        this(compatibilityQueries, taskId -> {
            throw new UnsupportedOperationException("projection audit is unavailable for this compatibility query service");
        });
    }

    TaskCompatibilityQueryService(TaskCompatibilityProjectionAccess compatibilityQueries,
                                  Function<String, TaskStateValidationResult> projectionAudit) {
        this.compatibilityQueries = Objects.requireNonNull(compatibilityQueries, "compatibilityQueries");
        this.projectionAudit = Objects.requireNonNull(projectionAudit, "projectionAudit");
    }

    /**
     * Bounded compatibility snapshot over stored residue plus lightweight
     * runtime overlays.
     *
     * <p>This is not the engine's runtime queue truth and must not be used to
     * infer total queued work for high-volume tasks.</p>
     */
    @CompatibilityProjectionOnly
    public TaskCompatibilitySnapshotPage visitTaskMessageSnapshot(String taskId,
                                                                 int limit,
                                                                 TaskCompatibilityMessageVisitor visitor) {
        return compatibilityQueries.visitTaskMessageSnapshot(taskId, limit, visitor);
    }

    /**
     * Single-message compatibility view.
     *
     * <p>For non-final tasks this prefers runtime work / active-lease recovery
     * before falling back to stored `TaskMsg` residue.</p>
     */
    @CompatibilityProjectionOnly
    public boolean visitTaskMessage(String taskId,
                                    String messageId,
                                    TaskCompatibilityMessageVisitor visitor) {
        return compatibilityQueries.visitTaskMessage(taskId, messageId, visitor);
    }

    /**
     * Compatibility attempt history view.
     *
     * <p>Stored attempt residue remains the history source; when a current
     * active attempt exists, runtime lease truth overlays that live attempt
     * into the emitted view.</p>
     */
    @CompatibilityProjectionOnly
    public void visitTaskMessageAttemptViews(String taskId,
                                             String messageId,
                                             TaskCompatibilityMessageAttemptVisitor visitor) {
        compatibilityQueries.visitTaskMessageAttemptViews(taskId, messageId, visitor);
    }

    /**
     * Current active-attempt compatibility view.
     *
     * <p>This is runtime-first while a lease is active; it must not require a
     * persisted `TaskMsgAttempt` row to reveal current attempt ownership.</p>
     */
    @CompatibilityProjectionOnly
    public boolean visitLatestActiveTaskMessageAttempt(String taskId,
                                                       String messageId,
                                                       TaskCompatibilityMessageAttemptVisitor visitor) {
        return compatibilityQueries.visitLatestActiveTaskMessageAttempt(taskId, messageId, visitor);
    }

    /**
     * Explicit deep projection audit over compatibility residue.
     *
     * <p>This is diagnostic-only and remains outside the default task query
     * surface so projection residue is not mistaken for runtime truth.</p>
     */
    @CompatibilityProjectionOnly
    public TaskStateValidationResult auditTaskProjectionState(String taskId) {
        return projectionAudit.apply(taskId);
    }
}
