package com.xa.mass.sdk;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.sdk.model.MassTaskCreateRequest;
import com.xa.mass.sdk.model.MassTaskRequest;

import java.util.List;
import java.util.Map;

public interface TaskOperations {

    Task createTask(MassTaskCreateRequest request);

    Task createTask(MassTaskRequest request);

    Task getTask(String taskId);

    List<Task> getAllTasks();

    List<Task> getTasksByStatus(TaskStatus status);

    boolean approveTask(String taskId);

    boolean rejectTask(String taskId);

    boolean blockTask(String taskId);

    boolean pauseTask(String taskId);

    SdkTaskResumeResult resumeTaskDetailed(String taskId);

    boolean cancelTask(String taskId);

    boolean terminateTask(String taskId, TaskTerminalReason reason);

    boolean updateTask(Task task);

    boolean deleteTask(String taskId);

    int appendTaskItems(String taskId, List<Map<String, Object>> inputs);

    boolean sealTask(String taskId);

    List<TaskMsg> getTaskMessages(String taskId);

    List<TaskMsg> getTaskMessagesPage(String taskId, int offset, int limit);

    long countTaskMessages(String taskId);

    Object validateTaskState(String taskId);

    Object resolveTaskStateFromMessages(String taskId);
}
