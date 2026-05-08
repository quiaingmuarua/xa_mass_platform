package com.xa.mass.engine;

import java.util.List;

/**
 * Explicit compatibility-read surface for TaskMsg / TaskMsgAttempt residue.
 *
 * <p>This seam exists only so shell, SDK, and migration callers can read the
 * bounded compatibility projection without treating it as the default engine
 * query model.</p>
 */
interface TaskCompatibilityQueryPort {

    CompatibilityTaskMessageSnapshot getTaskMessageSnapshot(String taskId, int limit);

    CompatibilityTaskMessageView getTaskMessageView(String taskId, String messageId);

    List<CompatibilityTaskMessageAttemptView> getTaskMessageAttemptViews(String taskId, String messageId);

    CompatibilityTaskMessageAttemptView getLatestActiveTaskMessageAttemptView(String taskId, String messageId);
}
