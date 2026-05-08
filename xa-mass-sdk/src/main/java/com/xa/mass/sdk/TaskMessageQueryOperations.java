package com.xa.mass.sdk;

import java.util.List;

/**
 * Explicit compatibility/read surface for bounded task-message residue views.
 */
public interface TaskMessageQueryOperations {

    SdkTaskMessageSnapshot getTaskMessageSnapshot(String taskId, int limit);

    SdkTaskMessageView getTaskMessageView(String taskId, String messageId);

    List<SdkTaskMessageAttemptView> getTaskMessageAttemptViews(String taskId, String messageId);

    SdkTaskMessageAttemptView getLatestActiveTaskMessageAttemptView(String taskId, String messageId);
}
