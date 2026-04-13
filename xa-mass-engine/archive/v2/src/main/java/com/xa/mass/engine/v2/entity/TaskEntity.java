package com.xa.mass.engine.v2.entity;

import com.xa.mass.base.model.User;

import java.util.Map;

public class TaskEntity {
    private String taskId;
    private String taskName;
    private String project;
    private String taskStatus; //NEW(新建任务) BLOCKED(审核完成) READY(分配资源中)  RUNNING(运行中可以被调度的任务) PAUSED(暂停) TERMINAL(结束、中止)
    private String taskCountry;
    private User user;
    private long  taskCount;
    private String textContent;
    private Map<String,Object> taskScheduleRules; //任务分配规则，比如batch_size
    private Map<String,Object> taskDeviceMatchRules; //任务绑定设备规则

    private long lockExpireTime;
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

    // Getter and Setter methods
    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    public String getTaskStatus() {
        return taskStatus;
    }

    public void setTaskStatus(String taskStatus) {
        this.taskStatus = taskStatus;
        this.updateTime=System.currentTimeMillis();
    }

    public String getTaskCountry() {
        return taskCountry;
    }

    public void setTaskCountry(String taskCountry) {
        this.taskCountry = taskCountry;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public long getTaskCount() {
        return taskCount;
    }

    public void setTaskCount(long taskCount) {
        this.taskCount = taskCount;
    }

    public String getTextContent() {
        return textContent;
    }

    public void setTextContent(String textContent) {
        this.textContent = textContent;
    }

    public Map<String, Object> getTaskScheduleRules() {
        return taskScheduleRules;
    }

    public void setTaskScheduleRules(Map<String, Object> taskScheduleRules) {
        this.taskScheduleRules = taskScheduleRules;
    }

    public Map<String, Object> getTaskDeviceMatchRules() {
        return taskDeviceMatchRules;
    }

    public void setTaskDeviceMatchRules(Map<String, Object> taskDeviceMatchRules) {
        this.taskDeviceMatchRules = taskDeviceMatchRules;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(long updateTime) {
        this.updateTime = updateTime;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }

    public long getLockExpireTime() {
        return lockExpireTime;
    }

    public void setLockExpireTime(long lockExpireTime) {
        this.lockExpireTime = lockExpireTime;
    }

    @Override
    public String toString() {
        return "TaskEntity{" +
                "taskId='" + taskId + '\'' +
                ", taskName='" + taskName + '\'' +
                ", project='" + project + '\'' +
                ", taskStatus='" + taskStatus + '\'' +
                ", taskCountry='" + taskCountry + '\'' +
                ", user=" + user +
                ", taskCount=" + taskCount +
                ", textContent='" + textContent + '\'' +
                ", taskScheduleRules=" + taskScheduleRules +
                ", taskDeviceMatchRules=" + taskDeviceMatchRules +
                ", lockExpireTime=" + lockExpireTime +
                ", createTime=" + createTime +
                ", updateTime=" + updateTime +
                ", startTime=" + startTime +
                ", endTime=" + endTime +
                '}';
    }
}
