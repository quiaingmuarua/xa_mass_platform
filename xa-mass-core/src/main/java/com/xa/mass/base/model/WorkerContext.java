package com.xa.mass.base.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.xa.mass.base.enums.worker.WorkerContextStatus;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * WorkerContext 实体（凭据/授权资源）
 * 负责授权与分配，是调度核心桥梁
 * 与 Worker 关联，生命周期与任务执行强相关
 */
public class WorkerContext {
    private String workerContextId;
    private String workerId;
    private ProjectRef project;
    private WorkerContextStatus status;
    private Set<String> routingTags = Collections.emptySet();
    private String lastBindTaskId;
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime lastUsedTime;
    private Map<String, String> attributes = Collections.emptyMap();

    public WorkerContext() {
        this.status = WorkerContextStatus.IDLE;
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }

    public WorkerContext(String workerContextId, String workerId, Set<String> routingTags) {
        this();
        this.workerContextId = workerContextId;
        this.workerId = workerId;
        this.routingTags = routingTags != null ? Collections.unmodifiableSet(new LinkedHashSet<>(routingTags)) : Collections.emptySet();
    }

    public String getWorkerContextId() {
        return workerContextId;
    }

    public void setWorkerContextId(String workerContextId) {
        this.workerContextId = workerContextId;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public String getProject() {
        return project == null ? null : project.getCode();
    }

    public void setProject(String project) {
        this.project = project == null ? null : ProjectRef.require(project);
    }

    @JsonIgnore
    public ProjectRef getProjectRef() {
        return project;
    }

    public void setProjectRef(ProjectRef project) {
        this.project = project == null ? null : ProjectRef.require(project.getCode());
    }

    public WorkerContextStatus getStatus() {
        return status;
    }

    public void setStatus(WorkerContextStatus status) {
        this.status = Objects.requireNonNull(status, "status");
        this.updateTime = LocalDateTime.now();
        if (status == WorkerContextStatus.OCCUPIED) {
            this.lastUsedTime = LocalDateTime.now();
        }
    }

    public Set<String> getRoutingTags() {
        return routingTags;
    }

    public void setRoutingTags(Set<String> routingTags) {
        if (routingTags == null || routingTags.isEmpty()) {
            this.routingTags = Collections.emptySet();
            return;
        }
        this.routingTags = Collections.unmodifiableSet(new LinkedHashSet<>(routingTags));
    }

    public String getLastBindTaskId() {
        return lastBindTaskId;
    }

    public void setLastBindTaskId(String lastBindTaskId) {
        this.lastBindTaskId = lastBindTaskId;
    }

    public LocalDateTime getExpireTime() {
        return expireTime;
    }

    public void setExpireTime(LocalDateTime expireTime) {
        this.expireTime = expireTime;
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

    public LocalDateTime getLastUsedTime() {
        return lastUsedTime;
    }

    public void setLastUsedTime(LocalDateTime lastUsedTime) {
        this.lastUsedTime = lastUsedTime;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            this.attributes = Collections.emptyMap();
            return;
        }
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public boolean isAllocatable() {
        return status.isAllocatable() && !isExpired();
    }

    public boolean isInUse() {
        return status.isInUse();
    }

    public boolean isReserved() {
        return status.isReserved();
    }

    public boolean isOccupied() {
        return status.isOccupied();
    }

    public boolean isExpired() {
        return expireTime != null && expireTime.isBefore(LocalDateTime.now());
    }

    public boolean isAvailable() {
        return status.isAvailable() && !isExpired();
    }

    public boolean isUsable() {
        return status.isUsable() && !isExpired();
    }

    public boolean bindToTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }
        if (status.canTransitionTo(WorkerContextStatus.RESERVED)) {
            setStatus(WorkerContextStatus.RESERVED);
            setLastBindTaskId(taskId);
            return true;
        }
        return false;
    }

    public boolean startOccupying() {
        if (status.canTransitionTo(WorkerContextStatus.OCCUPIED)) {
            setStatus(WorkerContextStatus.OCCUPIED);
            return true;
        }
        return false;
    }

    public boolean release() {
        if (status == WorkerContextStatus.RESERVED || status == WorkerContextStatus.OCCUPIED) {
            setStatus(WorkerContextStatus.IDLE);
            setLastBindTaskId(null);
            return true;
        }
        return false;
    }

    public boolean block() {
        if (status.canTransitionTo(WorkerContextStatus.BLOCKED)) {
            setStatus(WorkerContextStatus.BLOCKED);
            return true;
        }
        return false;
    }

    public boolean unblock() {
        if (status.canTransitionTo(WorkerContextStatus.IDLE)) {
            setStatus(WorkerContextStatus.IDLE);
            return true;
        }
        return false;
    }

    public boolean invalidate() {
        if (status.canTransitionTo(WorkerContextStatus.INVALID)) {
            setStatus(WorkerContextStatus.INVALID);
            return true;
        }
        return false;
    }

    public boolean transitionTo(WorkerContextStatus targetStatus) {
        if (status.canTransitionTo(targetStatus)) {
            setStatus(targetStatus);
            return true;
        }
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WorkerContext that = (WorkerContext) o;
        return Objects.equals(workerContextId, that.workerContextId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workerContextId);
    }

    @Override
    public String toString() {
        return "WorkerContext{" +
                "workerContextId='" + workerContextId + '\'' +
                ", workerId='" + workerId + '\'' +
                ", project='" + project + '\'' +
                ", status=" + status +
                ", routingTags=" + routingTags +
                ", attributes=" + attributes +
                ", lastBindTaskId='" + lastBindTaskId + '\'' +
                ", isExpired=" + isExpired() +
                '}';
    }
}
