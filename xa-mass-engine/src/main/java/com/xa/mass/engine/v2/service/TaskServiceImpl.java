package com.xa.mass.engine.v2.service;

import com.xa.mass.engine.v2.dao.TaskRepositoryManager;
import com.xa.mass.engine.v2.entity.TaskEntity;
import com.xa.mass.engine.v2.entity.TaskMsgEntity;

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
    public void createTask(TaskEntity taskEntity) {
        Objects.requireNonNull(taskEntity, "Task entity cannot be null");
        Objects.requireNonNull(taskEntity.getTaskId(), "Task ID cannot be null");
        
        // 保存任务实体
        repository.saveTask(taskEntity);
        
        // 创建关联的队列
        repository.createSeedQueue(taskEntity.getTaskId());
        repository.createMsgQueue(taskEntity.getTaskId());
    }

    @Override
    public void addTaskSeed(String taskId, String seed) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        Objects.requireNonNull(seed, "Seed cannot be null");
        
        if (!repository.containsTask(taskId)) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        
        repository.addSeed(taskId, seed);
    }

    @Override
    public void batchAddSeed(String taskId, String[] seeds) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        Objects.requireNonNull(seeds, "Seeds cannot be null");
        
        if (!repository.containsTask(taskId)) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        
        repository.addSeeds(taskId, seeds);
    }

    @Override
    public String getTaskSeed(String taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        
        if (!repository.containsTask(taskId)) {
            return null;
        }
        
        return repository.pollSeed(taskId);
    }

    @Override
    public void updateTaskStatus(String taskId, String status) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        Objects.requireNonNull(status, "Status cannot be null");
        
        TaskEntity task = repository.getTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        
        task.setTaskStatus(status);
        task.setUpdateTime(System.currentTimeMillis());
        repository.saveTask(task);
    }

    @Override
    public void addTaskMsg(String taskId, TaskMsgEntity taskMsg) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        Objects.requireNonNull(taskMsg, "Task message cannot be null");
        
        if (!repository.containsTask(taskId)) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        
        repository.addMsg(taskId, taskMsg);
    }

    @Override
    public TaskMsgEntity getTaskMsg(String taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        
        if (!repository.containsTask(taskId)) {
            return null;
        }
        
        return repository.pollMsg(taskId);
    }

    @Override
    public TaskEntity getTask(String taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        return repository.getTask(taskId);
    }

    @Override
    public boolean containsTask(String taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        return repository.containsTask(taskId);
    }

    @Override
    public int getTaskSeedCount(String taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        
        if (!repository.containsTask(taskId)) {
            return 0;
        }
        
        return repository.getSeedCount(taskId);
    }

    @Override
    public int getTaskMsgCount(String taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        
        if (!repository.containsTask(taskId)) {
            return 0;
        }
        
        return repository.getMsgCount(taskId);
    }

    @Override
    public int getTotalTaskCount() {
        return repository.getTotalTaskCount();
    }
} 