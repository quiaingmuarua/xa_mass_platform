package com.xa.mass.sdk;

import com.xa.mass.sdk.model.TaskStageEvidenceRequest;
import com.xa.mass.sdk.model.TaskStageEvidenceSnapshot;
import com.xa.mass.sdk.model.TaskStageProjectionSnapshot;

import java.util.List;

/**
 * Owner-backed task stage evidence SDK surface.
 */
public interface TaskStageEvidenceOperations {

    TaskStageEvidenceSnapshot reportTaskStageEvidence(TaskStageEvidenceRequest request);

    TaskStageProjectionSnapshot getTaskStageProjection(String taskId, String messageId, String stageName);

    List<TaskStageProjectionSnapshot> listTaskStageProjections(String taskId, String messageId);
}
