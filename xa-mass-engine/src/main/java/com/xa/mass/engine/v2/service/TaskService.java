package com.xa.mass.engine.v2.service;

import com.xa.mass.engine.v2.entity.TaskEntity;

import java.util.List;

public interface TaskService {
    boolean createTask(TaskEntity task);
    boolean updateTask(TaskEntity task);

    boolean changeTaskStatus(String status);
    boolean addTaskSeed(List<String> seeds);
    boolean deleteTask(String taskId);
    TaskEntity getTaskById(String taskId);

}
