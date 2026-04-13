package com.xa.mass.engine.v2.service;

import com.xa.mass.engine.v2.entity.TaskEntity;
import com.xa.mass.engine.v2.entity.TaskMsgEntity;
import com.xa.mass.base.enums.Project;

public interface TaskService {
    // 任务实体操作
    void createTask(Project project, TaskEntity taskEntity);
    TaskEntity getTask(Project project, String taskId);
    boolean containsTask(Project project, String taskId);
    
    // 种子操作
    void addTaskSeed(Project project, String taskId, String seed);
    void batchAddSeed(Project project, String taskId, String[] seeds);
    String getTaskSeed(Project project, String taskId);
    int getTaskSeedCount(Project project, String taskId);
    
    // 任务状态操作
    void updateTaskStatus(Project project, String taskId, String status);
    
    // 消息操作
    void addTaskMsg(Project project, String taskId, TaskMsgEntity taskMsg);
    TaskMsgEntity getTaskMsg(Project project, String taskId);
    int getTaskMsgCount(Project project, String taskId);
    
    // 统计信息
    int getTotalTaskCount(Project project);
}
