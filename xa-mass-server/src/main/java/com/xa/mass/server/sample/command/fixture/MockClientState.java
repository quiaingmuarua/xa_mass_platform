package com.xa.mass.server.sample.command.fixture;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-worker mock behavior state. This is the single source of truth for
 * client-side fault injection in the dev app.
 */
public class MockClientState {

    public enum DropMode {
        OFF,
        ONCE,
        ALWAYS;

        public static DropMode fromValue(String value) {
            if (value == null || value.isBlank()) {
                return OFF;
            }
            return switch (value.trim().toUpperCase()) {
                case "OFF" -> OFF;
                case "ONCE" -> ONCE;
                case "ALWAYS", "ON" -> ALWAYS;
                default -> throw new IllegalArgumentException("unsupported drop mode: " + value);
            };
        }
    }

    private long taskResponseDelayMillis;
    private DropMode taskResponseDropMode = DropMode.OFF;
    private String taskResultStatusOverride;

    public synchronized long getTaskResponseDelayMillis() {
        return taskResponseDelayMillis;
    }

    public synchronized void setTaskResponseDelayMillis(long taskResponseDelayMillis) {
        this.taskResponseDelayMillis = Math.max(0L, taskResponseDelayMillis);
    }

    public synchronized DropMode getTaskResponseDropMode() {
        return taskResponseDropMode;
    }

    public synchronized void setTaskResponseDropMode(DropMode taskResponseDropMode) {
        this.taskResponseDropMode = taskResponseDropMode == null ? DropMode.OFF : taskResponseDropMode;
    }

    public synchronized String getTaskResultStatusOverride() {
        return taskResultStatusOverride;
    }

    public synchronized void setTaskResultStatusOverride(String taskResultStatusOverride) {
        if (taskResultStatusOverride == null || taskResultStatusOverride.isBlank()) {
            this.taskResultStatusOverride = null;
            return;
        }
        this.taskResultStatusOverride = taskResultStatusOverride.trim().toUpperCase();
    }

    public synchronized String resolveTaskResultStatus(String defaultStatus) {
        return taskResultStatusOverride == null ? defaultStatus : taskResultStatusOverride;
    }

    public synchronized boolean shouldDropTaskResponse() {
        if (taskResponseDropMode == DropMode.OFF) {
            return false;
        }
        if (taskResponseDropMode == DropMode.ONCE) {
            taskResponseDropMode = DropMode.OFF;
            return true;
        }
        return true;
    }

    public synchronized void reset() {
        this.taskResponseDelayMillis = 0L;
        this.taskResponseDropMode = DropMode.OFF;
        this.taskResultStatusOverride = null;
    }

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("taskResponseDelayMillis", taskResponseDelayMillis);
        snapshot.put("taskResponseDropMode", taskResponseDropMode.name());
        snapshot.put("taskResultStatusOverride", taskResultStatusOverride);
        return snapshot;
    }
}
