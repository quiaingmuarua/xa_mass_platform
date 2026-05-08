package com.xa.mass.engine;

import com.xa.mass.base.model.TaskMessageSnapshot;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;

import java.util.List;

/**
 * Explicit compatibility-read surface for TaskMsg / TaskMsgAttempt residue.
 *
 * <p>This seam exists only so shell, SDK, and migration callers can read the
 * bounded compatibility projection without treating it as the default engine
 * query model.</p>
 */
interface TaskCompatibilityQueryPort {

    TaskMessageSnapshot getTaskMessageSnapshot(String taskId, int limit);

    TaskMsg getTaskMessageView(String taskId, String messageId);

    List<TaskMsgAttempt> getTaskMessageAttemptViews(String taskId, String messageId);

    TaskMsgAttempt getLatestActiveTaskMessageAttemptView(String taskId, String messageId);
}
