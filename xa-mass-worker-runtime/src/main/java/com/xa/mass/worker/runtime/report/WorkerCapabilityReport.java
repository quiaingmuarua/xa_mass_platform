package com.xa.mass.worker.runtime.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Worker-originated capability slice owned by {@link WorkerCapabilityAuthority}.
 *
 * <p>The report replaces only the report-owned slice for one worker. It does
 * not create worker identity, worker group identity, project bounds, or
 * administrative event-binding ceilings.</p>
 */
public final class WorkerCapabilityReport {

    private final String workerId;
    private final long capabilityVersion;
    private final List<String> availableEventCodes;
    private final Map<String, String> schedulingAttributes;
    private final String agentVersion;

    private WorkerCapabilityReport(Builder builder) {
        this.workerId = requireNonBlank(builder.workerId, "workerId");
        if (builder.capabilityVersion < 0) {
            throw new IllegalArgumentException("capabilityVersion must be >= 0");
        }
        this.capabilityVersion = builder.capabilityVersion;
        this.availableEventCodes = immutableStringList(builder.availableEventCodes);
        this.schedulingAttributes = immutableStringMap(builder.schedulingAttributes);
        this.agentVersion = normalizeNullable(builder.agentVersion);
    }

    public static Builder builder(String workerId, long capabilityVersion) {
        return new Builder(workerId, capabilityVersion);
    }

    public String workerId() {
        return workerId;
    }

    public long capabilityVersion() {
        return capabilityVersion;
    }

    public List<String> availableEventCodes() {
        return availableEventCodes;
    }

    public Map<String, String> schedulingAttributes() {
        return schedulingAttributes;
    }

    public String agentVersion() {
        return agentVersion;
    }

    private static List<String> immutableStringList(Iterable<String> values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String item = normalizeNullable(value);
            if (item != null) {
                normalized.add(item);
            }
        }
        return normalized.isEmpty() ? List.of() : Collections.unmodifiableList(new ArrayList<>(normalized));
    }

    private static Map<String, String> immutableStringMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = normalizeNullable(entry.getKey());
            String value = normalizeNullable(entry.getValue());
            if (key != null && value != null) {
                normalized.put(key, value);
            }
        }
        return normalized.isEmpty() ? Map.of() : Collections.unmodifiableMap(normalized);
    }

    private static String requireNonBlank(String value, String fieldName) {
        String normalized = normalizeNullable(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WorkerCapabilityReport that)) {
            return false;
        }
        return capabilityVersion == that.capabilityVersion
                && Objects.equals(workerId, that.workerId)
                && Objects.equals(availableEventCodes, that.availableEventCodes)
                && Objects.equals(schedulingAttributes, that.schedulingAttributes)
                && Objects.equals(agentVersion, that.agentVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workerId, capabilityVersion, availableEventCodes,
                schedulingAttributes, agentVersion);
    }

    public static final class Builder {
        private final String workerId;
        private final long capabilityVersion;
        private List<String> availableEventCodes = List.of();
        private Map<String, String> schedulingAttributes = Map.of();
        private String agentVersion;

        private Builder(String workerId, long capabilityVersion) {
            this.workerId = workerId;
            this.capabilityVersion = capabilityVersion;
        }

        public Builder availableEventCodes(List<String> availableEventCodes) {
            this.availableEventCodes = availableEventCodes == null ? List.of() : availableEventCodes;
            return this;
        }

        public Builder schedulingAttributes(Map<String, String> schedulingAttributes) {
            this.schedulingAttributes = schedulingAttributes == null ? Map.of() : schedulingAttributes;
            return this;
        }

        public Builder agentVersion(String agentVersion) {
            this.agentVersion = agentVersion;
            return this;
        }

        public WorkerCapabilityReport build() {
            return new WorkerCapabilityReport(this);
        }
    }
}
