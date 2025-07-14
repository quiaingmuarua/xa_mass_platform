package com.xa.mass.engine.v2.service;

import com.xa.mass.engine.v2.dao.TaskRepositoryManager;
import com.xa.mass.engine.v2.entity.TaskEntity;
import com.xa.mass.engine.v2.entity.TaskMsgEntity;
import com.xa.mass.base.enums.Project;
import com.xa.mass.engine.v2.util.QueueKeyUtil;

import java.util.Objects;

/**
 * 任务服务实现类
 */
public class TaskServiceImpl implements TaskService {

    private final TaskRepositoryManager repository;

    public TaskServiceImpl(TaskRepositoryManager repository) {
        this.repository = Objects.requireNonNull(repository, "Repository cannot be null");
    }

    @Override
    public void createTask(Project project, TaskEntity taskEntity) {
        Objects.requireNonNull(taskEntity, "Task entity cannot be null");
        Objects.requireNonNull(taskEntity.getTaskId(), "Task ID cannot be null");
        
        // 保存任务实体
        repository.saveTask(project, taskEntity);
        
        // 创建关联的流
        repository.createSeedStream(QueueKeyUtil.getSeedStreamKey(project, taskEntity.getTaskId()));
        repository.createMsgStream(QueueKeyUtil.getMsgStreamKey(project, taskEntity.getTaskId()));
    }

    @Override
    public void addTaskSeed(Project project, String taskId, String seed) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        Objects.requireNonNull(seed, "Seed cannot be null");
        
        if (!repository.containsTask(project, taskId)) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        
        repository.addSeed(QueueKeyUtil.getSeedStreamKey(project, taskId), seed);
    }

    @Override
    public void batchAddSeed(Project project, String taskId, String[] seeds) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        Objects.requireNonNull(seeds, "Seeds cannot be null");
        if (!repository.containsTask(project, taskId)) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        for (String seed : seeds) {
            repository.addSeed(QueueKeyUtil.getSeedStreamKey(project, taskId), seed);
        }
    }

    @Override
    public String getTaskSeed(Project project, String taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        if (!repository.containsTask(project, taskId)) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        return repository.getSeed(QueueKeyUtil.getSeedStreamKey(project, taskId));
    }

    @Override
    public void updateTaskStatus(Project project, String taskId, String status) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        Objects.requireNonNull(status, "Status cannot be null");
        
        TaskEntity task = repository.getTask(project, taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        
        task.setTaskStatus(status);
        task.setUpdateTime(System.currentTimeMillis());
        repository.saveTask(project, task);
    }

    @Override
    public void addTaskMsg(Project project, String taskId, TaskMsgEntity taskMsg) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        Objects.requireNonNull(taskMsg, "Task message cannot be null");
        
        if (!repository.containsTask(project, taskId)) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        
        repository.addMsg(QueueKeyUtil.getMsgStreamKey(project, taskId), taskMsg);
    }

    @Override
    public TaskMsgEntity getTaskMsg(Project project, String taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        if (!repository.containsTask(project, taskId)) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        return repository.getMsg(QueueKeyUtil.getMsgStreamKey(project, taskId));
    }

    @Override
    public TaskEntity getTask(Project project, String taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        return repository.getTask(project, taskId);
    }

    @Override
    public boolean containsTask(Project project, String taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        return repository.containsTask(project, taskId);
    }

    @Override
    public int getTaskSeedCount(Project project, String taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        
        if (!repository.containsTask(project, taskId)) {
            return 0;
        }
        
        return repository.getSeedCount(QueueKeyUtil.getSeedStreamKey(project, taskId));
    }

    @Override
    public int getTaskMsgCount(Project project, String taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        
        if (!repository.containsTask(project, taskId)) {
            return 0;
        }
        
        return repository.getMsgCount(QueueKeyUtil.getMsgStreamKey(project, taskId));
    }

    @Override
    public int getTotalTaskCount(Project project) {
        Objects.requireNonNull(project, "Project cannot be null");
        return repository.getProjectTaskCount(project);
    }
} 