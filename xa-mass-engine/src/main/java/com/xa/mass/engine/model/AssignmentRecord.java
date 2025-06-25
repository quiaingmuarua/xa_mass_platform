package com.xa.mass.engine.model;

import com.xa.mass.engine.monkey.snapshot.DeviceSnapshot;
import com.xa.mass.engine.monkey.snapshot.TaskSnapshot;
import com.xa.mass.engine.monkey.snapshot.TokenSnapshot;
import com.xa.mass.eventbus.enums.assignment.AssignmentResult;
import com.xa.mass.eventbus.enums.assignment.AssignmentType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 分配记录模型
 * 记录所有分配尝试的详细信息，支持归因分析和验证
 */
public class AssignmentRecord {

    /**
     * 记录ID
     */
    private String recordId;

    /**
     * 分配类型：DEVICE_ASSIGN（设备分配）、MSG_ASSIGN（消息分配）
     */
    private AssignmentType type;

    /**
     * 任务ID
     */
    private String taskId;

    /**
     * 设备ID
     */
    private String deviceId;

    /**
     * 消息ID（消息分配时使用）
     */
    private String messageId;

    /**
     * 批次ID
     */
    private String batchId;

    /**
     * 分配结果：SUCCESS、FAILED、CONFLICT、SKIPPED
     */
    private AssignmentResult result;

    /**
     * 分配时间
     */
    private LocalDateTime assignTime;

    /**
     * 归因说明
     */
    private String reason;

    /**
     * 规则评估详情
     */
    private List<RuleEvaluationDetail> ruleEvaluations;

    /**
     * 上下文属性快照
     */
    private Map<String, Object> contextSnapshot;

    /**
     * 资源冲突信息
     */
    private String conflictInfo;

    /**
     * 分配优先级
     */
    private Integer priority;

    /**
     * 任务属性快照
     */
    private TaskSnapshot taskSnapshot;

    /**
     * 设备属性快照
     */
    private DeviceSnapshot deviceSnapshot;

    /**
     * Token属性快照
     */
    private TokenSnapshot tokenSnapshot;

    public AssignmentRecord() {
        this.assignTime = LocalDateTime.now();
    }

    // Getters and Setters
    public String getRecordId() {
        return recordId;
    }

    public void setRecordId(String recordId) {
        this.recordId = recordId;
    }

    public AssignmentType getType() {
        return type;
    }

    public void setType(AssignmentType type) {
        this.type = type;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public AssignmentResult getResult() {
        return result;
    }

    public void setResult(AssignmentResult result) {
        this.result = result;
    }

    public LocalDateTime getAssignTime() {
        return assignTime;
    }

    public void setAssignTime(LocalDateTime assignTime) {
        this.assignTime = assignTime;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public List<RuleEvaluationDetail> getRuleEvaluations() {
        return ruleEvaluations;
    }

    public void setRuleEvaluations(List<RuleEvaluationDetail> ruleEvaluations) {
        this.ruleEvaluations = ruleEvaluations;
    }

    public Map<String, Object> getContextSnapshot() {
        return contextSnapshot;
    }

    public void setContextSnapshot(Map<String, Object> contextSnapshot) {
        this.contextSnapshot = contextSnapshot;
    }

    public String getConflictInfo() {
        return conflictInfo;
    }

    public void setConflictInfo(String conflictInfo) {
        this.conflictInfo = conflictInfo;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public TaskSnapshot getTaskSnapshot() {
        return taskSnapshot;
    }

    public void setTaskSnapshot(TaskSnapshot taskSnapshot) {
        this.taskSnapshot = taskSnapshot;
    }

    public DeviceSnapshot getDeviceSnapshot() {
        return deviceSnapshot;
    }

    public void setDeviceSnapshot(DeviceSnapshot deviceSnapshot) {
        this.deviceSnapshot = deviceSnapshot;
    }

    public TokenSnapshot getTokenSnapshot() {
        return tokenSnapshot;
    }

    public void setTokenSnapshot(TokenSnapshot tokenSnapshot) {
        this.tokenSnapshot = tokenSnapshot;
    }

    @Override
    public String toString() {
        return String.format("AssignmentRecord{recordId='%s', type=%s, taskId='%s', deviceId='%s', result=%s, reason='%s'}",
                recordId, type, taskId, deviceId, result, reason);
    }
} 