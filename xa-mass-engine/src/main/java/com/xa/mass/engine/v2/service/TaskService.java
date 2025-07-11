package com.xa.mass.engine.v2.service;

import com.xa.mass.engine.v2.entity.TaskEntity;
import com.xa.mass.engine.v2.entity.TaskMsgEntity;

public interface TaskService {
    // 任务实体操作
    void createTask(TaskEntity taskEntity);
    TaskEntity getTask(String taskId);
    boolean containsTask(String taskId);
    
    // 种子操作
    void addTaskSeed(String taskId, String seed);
    void batchAddSeed(String taskId, String[] seeds);
    String getTaskSeed(String taskId);
    int getTaskSeedCount(String taskId);
    
    // 任务状态操作
    void updateTaskStatus(String taskId, String status);
    
    // 消息操作
    void addTaskMsg(String taskId, TaskMsgEntity taskMsg);
    TaskMsgEntity getTaskMsg(String taskId);
    int getTaskMsgCount(String taskId);
    
    // 统计信息
    int getTotalTaskCount();
}
