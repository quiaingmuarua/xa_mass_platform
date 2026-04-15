package com.xa.mass.engine.monkey.snapshot;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * WorkerContext 属性快照
 */
public class WorkerContextSnapshot {
    private String workerContextId;
    private String workerId;
    private String workerContextStatus;
    private String channel;
    private Map<String, String> attributes;
    private String lastBindTaskId;
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime lastUsedTime;
    private boolean isWorkerContextAllocatable;
    private boolean isWorkerContextAvailable;

    public WorkerContextSnapshot() {}

    public String getWorkerContextId() { return workerContextId; }
    public void setWorkerContextId(String workerContextId) { this.workerContextId = workerContextId; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public String getWorkerContextStatus() { return workerContextStatus; }
    public void setWorkerContextStatus(String workerContextStatus) { this.workerContextStatus = workerContextStatus; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public Map<String, String> getAttributes() { return attributes; }
    public void setAttributes(Map<String, String> attributes) { this.attributes = attributes; }
    public String getLastBindTaskId() { return lastBindTaskId; }
    public void setLastBindTaskId(String lastBindTaskId) { this.lastBindTaskId = lastBindTaskId; }
    public LocalDateTime getExpireTime() { return expireTime; }
    public void setExpireTime(LocalDateTime expireTime) { this.expireTime = expireTime; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
    public LocalDateTime getLastUsedTime() { return lastUsedTime; }
    public void setLastUsedTime(LocalDateTime lastUsedTime) { this.lastUsedTime = lastUsedTime; }
    public boolean isWorkerContextAllocatable() { return isWorkerContextAllocatable; }
    public void setWorkerContextAllocatable(boolean workerContextAllocatable) { isWorkerContextAllocatable = workerContextAllocatable; }
    public boolean isWorkerContextAvailable() { return isWorkerContextAvailable; }
    public void setWorkerContextAvailable(boolean workerContextAvailable) { isWorkerContextAvailable = workerContextAvailable; }
}
