package com.xa.mass.api.auth;

import java.util.Arrays;
import java.util.Locale;

public enum OperatorAuthMode {
    DEV_HEADER("dev-header"),
    SESSION("session"),
    DISABLED("disabled");

    private final String configValue;

    OperatorAuthMode(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    public static OperatorAuthMode parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("operator auth mode is blank");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return Arrays.stream(values())
                .filter(mode -> mode.configValue.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported mass.auth.operator.mode: " + value));
    }
}
