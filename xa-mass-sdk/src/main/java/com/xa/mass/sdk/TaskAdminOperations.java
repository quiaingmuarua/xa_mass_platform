package com.xa.mass.sdk;

import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.sdk.model.MassTaskShellCreateRequest;
import com.xa.mass.sdk.model.MassTaskCreateRequest;
import com.xa.mass.sdk.model.MassTaskRequest;
import com.xa.mass.sdk.model.MassTaskUpdateRequest;

import java.util.List;
import java.util.Map;

/**
 * Task mutation/admin surface used by embedded shells and repo-local tooling.
 */
public interface TaskAdminOperations {

    com.xa.mass.base.model.Task createTaskShell(MassTaskShellCreateRequest request);

    com.xa.mass.base.model.Task createTask(MassTaskCreateRequest request);

    com.xa.mass.base.model.Task createTask(MassTaskRequest request);

    boolean approveTask(String taskId);

    boolean rejectTask(String taskId);

    boolean blockTask(String taskId);

    boolean pauseTask(String taskId);

    SdkTaskResumeResult resumeTaskDetailed(String taskId);

    boolean cancelTask(String taskId);

    boolean terminateTask(String taskId, TaskTerminalReason reason);

    boolean updateTaskDefinition(String taskId, MassTaskUpdateRequest request);

    boolean deleteTask(String taskId);

    int appendTaskItems(String taskId, List<Map<String, Object>> inputs);

    int appendTaskItems(String taskId, List<Map<String, Object>> inputs, int defaultMsgMaxRetryCount);

    boolean sealTask(String taskId);
}
