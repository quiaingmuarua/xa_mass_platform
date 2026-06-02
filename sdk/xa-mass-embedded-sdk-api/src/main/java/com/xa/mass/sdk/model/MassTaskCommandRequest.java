package com.xa.mass.sdk.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Stable SDK-native task command request.
 */
public final class MassTaskCommandRequest {

    private final String command;
    private final String reason;
    private final Map<String, Object> options;

    private MassTaskCommandRequest(Builder builder) {
        this.command = normalizeString(builder.command);
        this.reason = normalizeString(builder.reason);
        this.options = unmodifiableMapCopy(builder.options);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getCommand() {
        return command;
    }

    public String getReason() {
        return reason;
    }

    public Map<String, Object> getOptions() {
        return options;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MassTaskCommandRequest that)) return false;
        return Objects.equals(command, that.command)
                && Objects.equals(reason, that.reason)
                && Objects.equals(options, that.options);
    }

    @Override
    public int hashCode() {
        return Objects.hash(command, reason, options);
    }

    @Override
    public String toString() {
        return "MassTaskCommandRequest{"
                + "command='" + command + '\''
                + ", reason='" + reason + '\''
                + ", options=" + options
                + '}';
    }

    public static final class Builder {
        private String command;
        private String reason;
        private Map<String, Object> options = Collections.emptyMap();

        private Builder() {
        }

        public Builder command(String command) {
            this.command = command;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder options(Map<String, Object> options) {
            this.options = options;
            return this;
        }

        public MassTaskCommandRequest build() {
            return new MassTaskCommandRequest(this);
        }
    }

    private static Map<String, Object> unmodifiableMapCopy(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        if (source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static String normalizeString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
