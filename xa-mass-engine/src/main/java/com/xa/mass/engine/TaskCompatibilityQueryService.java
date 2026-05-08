package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;
import java.util.Objects;

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

    public TaskCompatibilityQueryService(TaskManager taskManager) {
        this(taskManager.compatibilityProjectionAccess());
    }

    TaskCompatibilityQueryService(TaskCompatibilityProjectionAccess compatibilityQueries) {
        this.compatibilityQueries = Objects.requireNonNull(compatibilityQueries, "compatibilityQueries");
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
}
