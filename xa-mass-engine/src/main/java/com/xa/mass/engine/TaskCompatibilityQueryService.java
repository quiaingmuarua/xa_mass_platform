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

    public TaskCompatibilitySnapshotPage visitTaskMessageSnapshot(String taskId,
                                                                 int limit,
                                                                 TaskCompatibilityMessageVisitor visitor) {
        return compatibilityQueries.visitTaskMessageSnapshot(taskId, limit, visitor);
    }

    public boolean visitTaskMessage(String taskId,
                                    String messageId,
                                    TaskCompatibilityMessageVisitor visitor) {
        return compatibilityQueries.visitTaskMessage(taskId, messageId, visitor);
    }

    public void visitTaskMessageAttemptViews(String taskId,
                                             String messageId,
                                             TaskCompatibilityMessageAttemptVisitor visitor) {
        compatibilityQueries.visitTaskMessageAttemptViews(taskId, messageId, visitor);
    }

    public boolean visitLatestActiveTaskMessageAttempt(String taskId,
                                                       String messageId,
                                                       TaskCompatibilityMessageAttemptVisitor visitor) {
        return compatibilityQueries.visitLatestActiveTaskMessageAttempt(taskId, messageId, visitor);
    }
}
