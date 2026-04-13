package com.xa.mass.base.model;


import com.xa.mass.base.enums.Project;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 任务实体
 * 管理"业务级"生命周期和状态，是发起点
 * 维护所有消息、进度、归属等信息
 */
public class Task {
    /**
     * 任务唯一ID
     */
    private String tid;

    /**
     * 任务名称
     */
    private String taskName;

    /**
     * 所属project/app
     */
    private Project project;

    /**
     * 状态
     */
    private TaskStatus status;

    /**
     * 区域/国家
     */
    private String taskCountry;

    /**
     * 总消息数
     */
    private int taskInitNumber;

    /**
     * 有效消息数
     */
    private int taskValidNumber;

    /**
     * 已完成消息数
     */
    private int taskExecutedNumber;

    /**
     * 剩余消息数
     */
    private int taskUnExecutedNumber;

    /**
     * 运行时最低设备数
     */
    private int runTaskMinDeviceCnt;

    /**
     * 当前调度设备数
     */
    private int scheduleDeviceCnt;

    /**
     * 任务内容/模版
     */
    private String textContent;

    /**
     * 所属用户/操作者
     */
    private User user;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 开始时间
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;

    /**
     * 每个设备批次消息数
     */
    private int batchSize;

    /**
     * 进入终态的业务原因。
     */
    private TaskTerminalReason terminalReason;

    public Task() {
        this.status = TaskStatus.NEW;
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }

    public Task(String tid, String taskName, String project, String taskCountry,
                int taskInitNumber, String textContent, User user) {
        this();
        this.tid = tid;
        this.taskName = taskName;
        this.project = Project.requireCode(project);
        this.taskCountry = taskCountry;
        this.taskInitNumber = taskInitNumber;
        this.taskValidNumber = taskInitNumber;
        this.taskUnExecutedNumber = taskInitNumber;
        this.textContent = textContent;
        this.user = user;
    }

    // Getters and Setters
    public String getTid() {
        return tid;
    }

    public void setTid(String tid) {
        this.tid = tid;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public Project getProject() {
        return project;
    }

    public void setProject(Project project) {
        this.project = project;
    }

    public void setProject(String projectCode) {
        this.project = Project.requireCode(projectCode);
    }

    public String getProjectCode() {
        return project != null ? project.getCode() : null;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
        this.updateTime = LocalDateTime.now();
    }

    public String getTaskCountry() {
        return taskCountry;
    }

    public void setTaskCountry(String taskCountry) {
        this.taskCountry = taskCountry;
    }

    public int getTaskInitNumber() {
        return taskInitNumber;
    }

    public void setTaskInitNumber(int taskInitNumber) {
        this.taskInitNumber = taskInitNumber;
    }

    public int getTaskValidNumber() {
        return taskValidNumber;
    }

    public void setTaskValidNumber(int taskValidNumber) {
        this.taskValidNumber = taskValidNumber;
    }

    public int getTaskExecutedNumber() {
        return taskExecutedNumber;
    }

    public void setTaskExecutedNumber(int taskExecutedNumber) {
        this.taskExecutedNumber = taskExecutedNumber;
        this.taskUnExecutedNumber = this.taskValidNumber - this.taskExecutedNumber;
        this.updateTime = LocalDateTime.now();
    }

    public int getTaskUnExecutedNumber() {
        return taskUnExecutedNumber;
    }

    public void setTaskUnExecutedNumber(int taskUnExecutedNumber) {
        this.taskUnExecutedNumber = taskUnExecutedNumber;
    }

    public int getRunTaskMinDeviceCnt() {
        return runTaskMinDeviceCnt;
    }

    public void setRunTaskMinDeviceCnt(int runTaskMinDeviceCnt) {
        this.runTaskMinDeviceCnt = runTaskMinDeviceCnt;
    }

    public int getScheduleDeviceCnt() {
        return scheduleDeviceCnt;
    }

    public void setScheduleDeviceCnt(int scheduleDeviceCnt) {
        this.scheduleDeviceCnt = scheduleDeviceCnt;
        this.updateTime = LocalDateTime.now();
    }

    public String getTextContent() {
        return textContent;
    }

    public void setTextContent(String textContent) {
        this.textContent = textContent;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public int getBatchSize() {
        return Math.max(batchSize, 1);
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public TaskTerminalReason getTerminalReason() {
        return terminalReason;
    }

    public void setTerminalReason(TaskTerminalReason terminalReason) {
        this.terminalReason = terminalReason;
    }

    /**
     * 检查任务是否可以调度
     */
    public boolean isSchedulable() {
        return status.isSchedulable() && taskUnExecutedNumber > 0;
    }

    /**
     * 检查任务是否已完成
     */
    public boolean isCompleted() {
        return status.isFinal() || taskUnExecutedNumber <= 0;
    }

    /**
     * 获取完成进度百分比
     */
    public double getProgressPercentage() {
        if (taskValidNumber == 0) {
            return 0.0;
        }
        return (double) taskExecutedNumber / taskValidNumber * 100;
    }

    /**
     * 状态转换
     */
    public boolean transitionTo(TaskStatus targetStatus) {
        if (status.canTransitionTo(targetStatus)) {
            setStatus(targetStatus);
            if (targetStatus == TaskStatus.RUNNING && startTime == null) {
                setStartTime(LocalDateTime.now());
            } else if (targetStatus.isFinal()) {
                setEndTime(LocalDateTime.now());
            }
            return true;
        }
        return false;
    }

    public boolean transitionTo(TaskStatus targetStatus, TaskTerminalReason terminalReason) {
        if (!targetStatus.isFinal()) {
            throw new IllegalArgumentException("Terminal reason is only valid for final task states");
        }
        if (transitionTo(targetStatus)) {
            this.terminalReason = terminalReason;
            return true;
        }
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Task task = (Task) o;
        return Objects.equals(tid, task.tid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tid);
    }

    @Override
    public String toString() {
        return "Task{" +
                "tid='" + tid + '\'' +
                ", taskName='" + taskName + '\'' +
                ", project='" + (project != null ? project.getCode() : null) + '\'' +
                ", status=" + status +
                ", taskCountry='" + taskCountry + '\'' +
                ", taskInitNumber=" + taskInitNumber +
                ", taskValidNumber=" + taskValidNumber +
                ", taskExecutedNumber=" + taskExecutedNumber +
                ", taskUnExecutedNumber=" + taskUnExecutedNumber +
                ", progress=" + String.format("%.1f%%", getProgressPercentage()) +
                ", batchSize=" + batchSize +
                ", terminalReason=" + terminalReason +
                '}';
    }
}
