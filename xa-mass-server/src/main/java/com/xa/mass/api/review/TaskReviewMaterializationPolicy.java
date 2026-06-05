package com.xa.mass.api.review;

import java.util.Map;
import java.util.Objects;

/**
 * Server-owned resolver for task review read-model materialization depth.
 */
public final class TaskReviewMaterializationPolicy {

    public static final String SHARED_CONFIG_KEY = "reviewMaterializationMode";

    private final TaskReviewMaterializationMode defaultMode;

    public TaskReviewMaterializationPolicy(TaskReviewMaterializationMode defaultMode) {
        this.defaultMode = Objects.requireNonNull(defaultMode, "defaultMode");
    }

    public static TaskReviewMaterializationPolicy offDefault() {
        return new TaskReviewMaterializationPolicy(TaskReviewMaterializationMode.OFF);
    }

    public static TaskReviewMaterializationPolicy terminalDefault() {
        return new TaskReviewMaterializationPolicy(TaskReviewMaterializationMode.TERMINAL);
    }

    public static TaskReviewMaterializationPolicy diagnosticDefault() {
        return new TaskReviewMaterializationPolicy(TaskReviewMaterializationMode.DIAGNOSTIC);
    }

    public static TaskReviewMaterializationPolicy fromDefaultMode(String defaultMode) {
        return new TaskReviewMaterializationPolicy(TaskReviewMaterializationMode.parse(defaultMode));
    }

    public TaskReviewMaterializationMode defaultMode() {
        return defaultMode;
    }

    public TaskReviewMaterializationMode modeFor(Map<String, Object> sharedConfig) {
        String configured = stringValue(sharedConfig, SHARED_CONFIG_KEY);
        return configured == null ? defaultMode : TaskReviewMaterializationMode.parse(configured);
    }

    public boolean shouldRecordTerminalFacts(Map<String, Object> sharedConfig) {
        return modeFor(sharedConfig).recordsTerminalFacts();
    }

    public boolean shouldRecordDiagnosticFacts(Map<String, Object> sharedConfig) {
        return modeFor(sharedConfig).recordsDiagnosticFacts();
    }

    private static String stringValue(Map<String, Object> sharedConfig, String key) {
        if (sharedConfig == null || key == null) {
            return null;
        }
        Object value = sharedConfig.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
