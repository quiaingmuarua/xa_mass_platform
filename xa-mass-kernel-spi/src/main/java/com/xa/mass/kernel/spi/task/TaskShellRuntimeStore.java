package com.xa.mass.kernel.spi.task;

import com.xa.mass.base.model.Task;

import java.util.Optional;

/** Runtime-kernel port for stable task shell CRUD by id. */
public interface TaskShellRuntimeStore {

    void saveTask(Task task);

    Optional<Task> getTask(String taskId);

    boolean updateTask(Task task);

    boolean deleteTask(String taskId);
}
