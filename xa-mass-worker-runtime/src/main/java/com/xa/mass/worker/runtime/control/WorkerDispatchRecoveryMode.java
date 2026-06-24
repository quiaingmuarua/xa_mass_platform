package com.xa.mass.worker.runtime.control;

import java.util.Locale;
import java.util.Map;

/**
 * Worker-owned policy for what evidence may participate in dispatch recovery.
 */
public enum WorkerDispatchRecoveryMode {
    EXPLICIT_ONLY,
    FRESHNESS_EVIDENCE;

    public static final String ATTRIBUTE_KEY = "dispatchRecoveryMode";

    public static WorkerDispatchRecoveryMode fromAttributes(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return EXPLICIT_ONLY;
        }
        return fromValue(attributes.get(ATTRIBUTE_KEY));
    }

    public static WorkerDispatchRecoveryMode fromValue(String value) {
        if (value == null || value.isBlank()) {
            return EXPLICIT_ONLY;
        }
        try {
            return WorkerDispatchRecoveryMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return EXPLICIT_ONLY;
        }
    }
}
