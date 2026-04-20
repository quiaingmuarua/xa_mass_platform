package com.xa.mass.sdk.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SDK-native task creation contract.
 *
 * <p>This request keeps the public embedding surface independent from the
 * engine DTO package and stays immutable after construction.
 */
public final class MassTaskCreateRequest {

    private final String userId;
    private final String project;
    private final String taskName;
    private final Map<String, Object> sharedConfig;
    private final List<Map<String, Object>> inputs;
    private final String routingCode;
    private final int batchSize;
    private final int defaultMsgMaxRetryCount;
    private final boolean openEnded;
    private final int maxRuntimeSeconds;

    private MassTaskCreateRequest(Builder builder) {
        this.userId = builder.userId;
        this.project = builder.project;
        this.taskName = builder.taskName;
        this.sharedConfig = unmodifiableMapCopy(builder.sharedConfig);
        this.inputs = unmodifiableInputListCopy(builder.inputs);
        this.routingCode = builder.routingCode;
        this.batchSize = builder.batchSize;
        this.defaultMsgMaxRetryCount = builder.defaultMsgMaxRetryCount;
        this.openEnded = builder.openEnded;
        this.maxRuntimeSeconds = builder.maxRuntimeSeconds;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getUserId() {
        return userId;
    }

    public String getProject() {
        return project;
    }

    public String getTaskName() {
        return taskName;
    }

    public Map<String, Object> getSharedConfig() {
        return sharedConfig;
    }

    public List<Map<String, Object>> getInputs() {
        return inputs;
    }

    public String getRoutingCode() {
        return routingCode;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public int getDefaultMsgMaxRetryCount() {
        return defaultMsgMaxRetryCount;
    }

    public boolean isOpenEnded() {
        return openEnded;
    }

    public int getMaxRuntimeSeconds() {
        return maxRuntimeSeconds;
    }

    private static Map<String, Object> unmodifiableMapCopy(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static List<Map<String, Object>> unmodifiableInputListCopy(List<Map<String, Object>> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> copy = new ArrayList<>(source.size());
        for (Map<String, Object> item : source) {
            copy.add(unmodifiableMapCopy(item));
        }
        return Collections.unmodifiableList(copy);
    }

    public static final class Builder {
        private String userId;
        private String project;
        private String taskName;
        private Map<String, Object> sharedConfig = Collections.emptyMap();
        private List<Map<String, Object>> inputs = Collections.emptyList();
        private String routingCode;
        private int batchSize;
        private int defaultMsgMaxRetryCount = 3;
        private boolean openEnded;
        private int maxRuntimeSeconds;

        private Builder() {
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder project(String project) {
            this.project = project;
            return this;
        }

        public Builder taskName(String taskName) {
            this.taskName = taskName;
            return this;
        }

        public Builder sharedConfig(Map<String, Object> sharedConfig) {
            this.sharedConfig = sharedConfig != null ? sharedConfig : Collections.emptyMap();
            return this;
        }

        public Builder inputs(List<Map<String, Object>> inputs) {
            this.inputs = inputs != null ? inputs : Collections.emptyList();
            return this;
        }

        public Builder targets(List<String> targets) {
            if (targets == null || targets.isEmpty()) {
                this.inputs = Collections.emptyList();
                return this;
            }
            List<Map<String, Object>> converted = new ArrayList<>(targets.size());
            for (String target : targets) {
                converted.add(Map.of("target", target));
            }
            this.inputs = converted;
            return this;
        }

        public Builder routingCode(String routingCode) {
            this.routingCode = routingCode;
            return this;
        }

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Builder defaultMsgMaxRetryCount(int defaultMsgMaxRetryCount) {
            this.defaultMsgMaxRetryCount = defaultMsgMaxRetryCount;
            return this;
        }

        public Builder openEnded(boolean openEnded) {
            this.openEnded = openEnded;
            return this;
        }

        public Builder maxRuntimeSeconds(int maxRuntimeSeconds) {
            this.maxRuntimeSeconds = maxRuntimeSeconds;
            return this;
        }

        public MassTaskCreateRequest build() {
            return new MassTaskCreateRequest(this);
        }
    }
}
