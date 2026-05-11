package com.xa.mass.sdk;

import com.xa.mass.sdk.model.MassTaskItemBatchAppendRequest;
import com.xa.mass.sdk.model.MassTaskShellCreateRequest;
import com.xa.mass.sdk.model.MassTaskUpdateRequest;
import com.xa.mass.sdk.model.TaskShellSnapshot;

/**
 * Task mutation/admin surface used by embedded shells and repo-local tooling.
 */
public interface TaskAdminOperations {

    TaskShellSnapshot createTaskShell(MassTaskShellCreateRequest request);

    boolean approveTask(String taskId);

    boolean rejectTask(String taskId);

    boolean blockTask(String taskId);

    boolean pauseTask(String taskId);

    SdkTaskResumeResult resumeTaskDetailed(String taskId);

    boolean cancelTask(String taskId);

    boolean terminateTask(String taskId, String reason);

    boolean updateTaskDefinition(String taskId, MassTaskUpdateRequest request);

    boolean deleteTask(String taskId);

    int appendTaskItems(String taskId, MassTaskItemBatchAppendRequest request);

    boolean sealTask(String taskId);
}
