package com.xa.mass.api.review;

import java.util.Locale;

/**
 * Server-owned task review materialization depth.
 */
public enum TaskReviewMaterializationMode {
    OFF,
    TERMINAL,
    DIAGNOSTIC;

    public boolean recordsTerminalFacts() {
        return this == TERMINAL || this == DIAGNOSTIC;
    }

    public boolean recordsDiagnosticFacts() {
        return this == DIAGNOSTIC;
    }

    public static TaskReviewMaterializationMode parse(String value) {
        if (value == null || value.isBlank()) {
            return TERMINAL;
        }
        String normalized = value.trim()
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "OFF", "NONE", "DISABLED" -> OFF;
            case "TERMINAL" -> TERMINAL;
            case "DIAGNOSTIC", "DEBUG", "FULL" -> DIAGNOSTIC;
            default -> throw new IllegalArgumentException(
                    "Unsupported task review materialization mode: " + value);
        };
    }
}
