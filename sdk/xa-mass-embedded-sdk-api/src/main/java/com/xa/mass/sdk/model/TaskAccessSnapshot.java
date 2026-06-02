package com.xa.mass.sdk.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TaskAccessSnapshot {

    private final String taskId;
    private final String project;
    private final Map<String, Object> sharedConfig;
    private final String intakeStatus;

    public TaskAccessSnapshot(String taskId, String project, Map<String, Object> sharedConfig, String intakeStatus) {
        this.taskId = taskId;
        this.project = project;
        this.sharedConfig = copyMap(sharedConfig);
        this.intakeStatus = intakeStatus;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getProject() {
        return project;
    }

    public Map<String, Object> getSharedConfig() {
        return sharedConfig;
    }

    public String getIntakeStatus() {
        return intakeStatus;
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
