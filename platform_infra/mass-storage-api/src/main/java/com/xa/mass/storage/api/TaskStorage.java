package com.xa.mass.storage.api;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Storage abstraction for the task control-plane aggregate. */
public interface TaskStorage {

    void saveTask(Task task);

    Optional<Task> getTask(String taskId);

    boolean updateTask(Task task);

    boolean deleteTask(String taskId);

    List<Task> getAllTasks();

    List<Task> getTasksByStatus(TaskStatus status);

    List<Task> getTasksByProject(String project);

    List<Task> getSchedulableTasks();

    List<Task> pollExpiredMaxRuntimeTasks(LocalDateTime now, int limit);
}
