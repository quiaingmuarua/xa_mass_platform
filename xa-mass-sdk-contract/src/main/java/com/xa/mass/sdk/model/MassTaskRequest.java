package com.xa.mass.sdk.model;

import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.TaskMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SDK-native task request with explicit mode and payload semantics.
 */
public final class MassTaskRequest {

    private final String userId;
    private final String project;
    private final String taskName;
    private final String eventCode;
    private final TaskMode mode;
    private final PayloadType payloadType;
    private final Map<String, Object> sharedConfig;
    private final List<MassInput> inputs;
    private final String routingCode;
    private final int batchSize;
    private final int defaultMsgMaxRetryCount;
    private final int maxRuntimeSeconds;

    private MassTaskRequest(Builder builder) {
        this.userId = builder.userId;
        this.project = builder.project;
        this.taskName = builder.taskName;
        this.eventCode = builder.eventCode;
        this.mode = builder.mode;
        this.payloadType = builder.payloadType;
        this.sharedConfig = unmodifiableMapCopy(builder.sharedConfig);
        this.inputs = unmodifiableInputs(builder.inputs);
        this.routingCode = builder.routingCode;
        this.batchSize = builder.batchSize;
        this.defaultMsgMaxRetryCount = builder.defaultMsgMaxRetryCount;
        this.maxRuntimeSeconds = builder.maxRuntimeSeconds;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder singleRun(String project, String taskName) {
        return builder()
                .project(project)
                .taskName(taskName)
                .mode(TaskMode.SINGLE_RUN);
    }

    public static Builder streaming(String project, String taskName) {
        return builder()
                .project(project)
                .taskName(taskName)
                .mode(TaskMode.STREAMING);
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

    public String getEventCode() {
        return eventCode;
    }

    public TaskMode getMode() {
        return mode;
    }

    public PayloadType getPayloadType() {
        return payloadType;
    }

    public Map<String, Object> getSharedConfig() {
        return sharedConfig;
    }

    public List<MassInput> getInputs() {
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

    public int getMaxRuntimeSeconds() {
        return maxRuntimeSeconds;
    }

    public boolean isStreaming() {
        return mode == TaskMode.STREAMING;
    }

    public List<Map<String, Object>> toEngineInputs() {
        List<Map<String, Object>> converted = new ArrayList<>(inputs.size());
        for (MassInput input : inputs) {
            converted.add(input.toTaskMsgInput());
        }
        return Collections.unmodifiableList(converted);
    }

    private static Map<String, Object> unmodifiableMapCopy(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static List<MassInput> unmodifiableInputs(List<MassInput> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(source));
    }

    public static final class Builder {
        private String userId;
        private String project;
        private String taskName;
        private String eventCode;
        private TaskMode mode = TaskMode.SINGLE_RUN;
        private PayloadType payloadType = PayloadType.JSON;
        private Map<String, Object> sharedConfig = Collections.emptyMap();
        private List<MassInput> inputs = Collections.emptyList();
        private String routingCode;
        private int batchSize;
        private int defaultMsgMaxRetryCount = 3;
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

        public Builder eventCode(String eventCode) {
            this.eventCode = eventCode;
            return this;
        }

        public Builder mode(TaskMode mode) {
            this.mode = mode != null ? mode : TaskMode.SINGLE_RUN;
            return this;
        }

        public Builder payloadType(PayloadType payloadType) {
            this.payloadType = payloadType != null ? payloadType : PayloadType.JSON;
            return this;
        }

        public Builder sharedConfig(Map<String, Object> sharedConfig) {
            this.sharedConfig = sharedConfig != null ? sharedConfig : Collections.emptyMap();
            return this;
        }

        public Builder inputs(List<MassInput> inputs) {
            this.inputs = inputs != null ? inputs : Collections.emptyList();
            return this;
        }

        public Builder textInputs(List<String> texts) {
            if (texts == null || texts.isEmpty()) {
                this.inputs = Collections.emptyList();
                return this;
            }
            List<MassInput> converted = new ArrayList<>(texts.size());
            for (String text : texts) {
                converted.add(new TextInput(text));
            }
            this.inputs = converted;
            this.payloadType = PayloadType.TEXT;
            return this;
        }

        public Builder jsonInputs(List<Map<String, Object>> jsonInputs) {
            if (jsonInputs == null || jsonInputs.isEmpty()) {
                this.inputs = Collections.emptyList();
                return this;
            }
            List<MassInput> converted = new ArrayList<>(jsonInputs.size());
            for (Map<String, Object> jsonInput : jsonInputs) {
                converted.add(new JsonInput(jsonInput));
            }
            this.inputs = converted;
            this.payloadType = PayloadType.JSON;
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

        public Builder maxRuntimeSeconds(int maxRuntimeSeconds) {
            this.maxRuntimeSeconds = maxRuntimeSeconds;
            return this;
        }

        public MassTaskRequest build() {
            return new MassTaskRequest(this);
        }
    }
}
