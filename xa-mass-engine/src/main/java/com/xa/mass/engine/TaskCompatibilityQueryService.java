package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;
import java.util.List;
import java.util.Objects;

/**
 * Explicit compatibility query facade for bounded TaskMsg / TaskMsgAttempt
 * residue reads.
 *
 * <p>This service is intentionally named as compatibility residue so callers
 * do not mistake TaskMsg projection reads for the engine's default runtime
 * query model.</p>
 */
@CompatibilityProjectionOnly
public class TaskCompatibilityQueryService {

    private final TaskCompatibilityQueryPort compatibilityQueries;

    public TaskCompatibilityQueryService(TaskManager taskManager) {
        this(taskManager.compatibilityProjectionAccess());
    }

    TaskCompatibilityQueryService(TaskCompatibilityQueryPort compatibilityQueries) {
        this.compatibilityQueries = Objects.requireNonNull(compatibilityQueries, "compatibilityQueries");
    }

    public CompatibilityTaskMessageSnapshot getTaskMessageSnapshot(String taskId, int limit) {
        return compatibilityQueries.getTaskMessageSnapshot(taskId, limit);
    }

    public CompatibilityTaskMessageView getTaskMessageView(String taskId, String messageId) {
        return compatibilityQueries.getTaskMessageView(taskId, messageId);
    }

    public List<CompatibilityTaskMessageAttemptView> getTaskMessageAttemptViews(String taskId, String messageId) {
        return compatibilityQueries.getTaskMessageAttemptViews(taskId, messageId);
    }

    public CompatibilityTaskMessageAttemptView getLatestActiveTaskMessageAttemptView(String taskId, String messageId) {
        return compatibilityQueries.getLatestActiveTaskMessageAttemptView(taskId, messageId);
    }
}
