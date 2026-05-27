package com.xa.mass.engine.model;

import com.xa.mass.base.enums.assignment.AssignmentResult;
import com.xa.mass.base.enums.assignment.AssignmentType;
import com.xa.mass.engine.monkey.snapshot.TaskSnapshot;
import com.xa.mass.engine.monkey.snapshot.WorkerSchedulingSnapshot;
import com.xa.mass.engine.monkey.snapshot.WorkerSnapshot;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 分配记录模型
 * 记录所有分配尝试的详细信息，支持归因分析和验证
 */
public class AssignmentRecord {

    private String recordId;
    private AssignmentType type;
    private String taskId;
    private String workerId;
    private String messageId;
    private String batchId;
    private AssignmentResult result;
    private LocalDateTime assignTime;
    private String reason;
    private List<RuleEvaluationDetail> ruleEvaluations;
    private int ruleEvaluationCount;
    private long ruleEvaluationTotalTimeMs;
    private Map<String, Object> contextSnapshot;
    private String conflictInfo;
    private Integer priority;
    private TaskSnapshot taskSnapshot;
    private WorkerSnapshot workerSnapshot;
    private WorkerSchedulingSnapshot workerSchedulingSnapshot;

    public AssignmentRecord() {
        this.assignTime = LocalDateTime.now();
    }

    public String getRecordId() { return recordId; }
    public void setRecordId(String recordId) { this.recordId = recordId; }

    public AssignmentType getType() { return type; }
    public void setType(AssignmentType type) { this.type = type; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }

    public AssignmentResult getResult() { return result; }
    public void setResult(AssignmentResult result) { this.result = result; }

    public LocalDateTime getAssignTime() { return assignTime; }
    public void setAssignTime(LocalDateTime assignTime) { this.assignTime = assignTime; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public List<RuleEvaluationDetail> getRuleEvaluations() { return ruleEvaluations; }
    public void setRuleEvaluations(List<RuleEvaluationDetail> ruleEvaluations) { this.ruleEvaluations = ruleEvaluations; }

    public int getRuleEvaluationCount() { return ruleEvaluationCount; }
    public void setRuleEvaluationCount(int ruleEvaluationCount) {
        this.ruleEvaluationCount = Math.max(0, ruleEvaluationCount);
    }

    public long getRuleEvaluationTotalTimeMs() { return ruleEvaluationTotalTimeMs; }
    public void setRuleEvaluationTotalTimeMs(long ruleEvaluationTotalTimeMs) {
        this.ruleEvaluationTotalTimeMs = Math.max(0L, ruleEvaluationTotalTimeMs);
    }

    public Map<String, Object> getContextSnapshot() { return contextSnapshot; }
    public void setContextSnapshot(Map<String, Object> contextSnapshot) { this.contextSnapshot = contextSnapshot; }

    public String getConflictInfo() { return conflictInfo; }
    public void setConflictInfo(String conflictInfo) { this.conflictInfo = conflictInfo; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public TaskSnapshot getTaskSnapshot() { return taskSnapshot; }
    public void setTaskSnapshot(TaskSnapshot taskSnapshot) { this.taskSnapshot = taskSnapshot; }

    public WorkerSnapshot getWorkerSnapshot() { return workerSnapshot; }
    public void setWorkerSnapshot(WorkerSnapshot workerSnapshot) { this.workerSnapshot = workerSnapshot; }

    public WorkerSchedulingSnapshot getWorkerSchedulingSnapshot() { return workerSchedulingSnapshot; }
    public void setWorkerSchedulingSnapshot(WorkerSchedulingSnapshot workerSchedulingSnapshot) {
        this.workerSchedulingSnapshot = workerSchedulingSnapshot;
    }

    @Override
    public String toString() {
        return String.format("AssignmentRecord{recordId='%s', type=%s, taskId='%s', workerId='%s', result=%s, reason='%s'}",
                recordId, type, taskId, workerId, result, reason);
    }
}
