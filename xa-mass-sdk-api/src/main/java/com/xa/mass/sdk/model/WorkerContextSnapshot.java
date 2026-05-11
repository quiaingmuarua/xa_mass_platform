package com.xa.mass.sdk.model;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class WorkerContextSnapshot {

    private final String workerContextId;
    private final String workerId;
    private final String project;
    private final String status;
    private final Set<String> routingTags;
    private final String lastBindTaskId;
    private final LocalDateTime expireTime;
    private final LocalDateTime createTime;
    private final LocalDateTime updateTime;
    private final LocalDateTime lastUsedTime;
    private final Map<String, String> attributes;

    public WorkerContextSnapshot(String workerContextId,
                                 String workerId,
                                 String project,
                                 String status,
                                 Set<String> routingTags,
                                 String lastBindTaskId,
                                 LocalDateTime expireTime,
                                 LocalDateTime createTime,
                                 LocalDateTime updateTime,
                                 LocalDateTime lastUsedTime,
                                 Map<String, String> attributes) {
        this.workerContextId = workerContextId;
        this.workerId = workerId;
        this.project = project;
        this.status = status;
        this.routingTags = copySet(routingTags);
        this.lastBindTaskId = lastBindTaskId;
        this.expireTime = expireTime;
        this.createTime = createTime;
        this.updateTime = updateTime;
        this.lastUsedTime = lastUsedTime;
        this.attributes = copyMap(attributes);
    }

    public String getWorkerContextId() {
        return workerContextId;
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getProject() {
        return project;
    }

    public String getStatus() {
        return status;
    }

    public Set<String> getRoutingTags() {
        return routingTags;
    }

    public String getLastBindTaskId() {
        return lastBindTaskId;
    }

    public LocalDateTime getExpireTime() {
        return expireTime;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public LocalDateTime getLastUsedTime() {
        return lastUsedTime;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    private static Set<String> copySet(Set<String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }

    private static Map<String, String> copyMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
