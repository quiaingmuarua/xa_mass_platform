package com.xa.mass.engine.v2.entity;

import com.xa.mass.base.model.User;
import lombok.Data;

import java.util.Map;

@Data
public class TaskEntity {
    private String taskId;
    private String taskName;
    private String project;
    private String taskStatus; //NEW BLOCKED READY RUNNING PAUSED TERMINAL
    private String taskCountry;
    private User user;
    private long  taskCount;
    private String textContent;
    private Map<String,Object> taskScheduleRules; //任务分配规则，比如batch_size
    private Map<String,Object> taskDeviceMatchRules; //任务绑定设备规则
    private long  createTime;
    private long  updateTime;
    private long  startTime;
    private long  endTime;

    // 构造函数
    public TaskEntity() {}

    public TaskEntity(String taskId, String taskName, String project) {
        this.taskId = taskId;
        this.taskName = taskName;
        this.project = project;
        this.taskStatus = "NEW";
        this.createTime = System.currentTimeMillis();
        this.updateTime = System.currentTimeMillis();
    }

    // 业务方法
    public boolean isReady() {
        return "READY".equals(taskStatus);
    }

    public boolean isRunning() {
        return "RUNNING".equals(taskStatus);
    }

    public boolean isCompleted() {
        return "COMPLETED".equals(taskStatus) || "TERMINAL".equals(taskStatus);
    }

    public void markAsReady() {
        this.taskStatus = "READY";
        this.updateTime = System.currentTimeMillis();
    }

    public void markAsRunning() {
        this.taskStatus = "RUNNING";
        this.startTime = System.currentTimeMillis();
        this.updateTime = System.currentTimeMillis();
    }

    public void markAsCompleted() {
        this.taskStatus = "COMPLETED";
        this.endTime = System.currentTimeMillis();
        this.updateTime = System.currentTimeMillis();
    }
}