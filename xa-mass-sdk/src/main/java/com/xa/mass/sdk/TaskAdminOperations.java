package com.xa.mass.sdk;

import com.xa.mass.sdk.model.MassTaskItemBatchAppendRequest;
import com.xa.mass.sdk.model.MassTaskCommandRequest;
import com.xa.mass.sdk.model.MassTaskShellCreateRequest;
import com.xa.mass.sdk.model.MassTaskUpdateRequest;
import com.xa.mass.sdk.model.TaskCommandResult;
import com.xa.mass.sdk.model.TaskShellSnapshot;

/**
 * Task mutation/admin surface used by embedded shells and repo-local tooling.
 */
public interface TaskAdminOperations {

    TaskShellSnapshot createTaskShell(MassTaskShellCreateRequest request);

    boolean updateTaskDefinition(String taskId, MassTaskUpdateRequest request);

    int appendTaskItems(String taskId, MassTaskItemBatchAppendRequest request);

    TaskCommandResult executeTaskCommand(String taskId, MassTaskCommandRequest request);
}
