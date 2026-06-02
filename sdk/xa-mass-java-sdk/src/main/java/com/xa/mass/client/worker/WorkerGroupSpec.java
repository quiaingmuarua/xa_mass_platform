package com.xa.mass.client.worker;

import java.util.List;
import java.util.Map;

public record WorkerGroupSpec(
        String groupId,
        List<WorkerEventBindingSpec> eventBindings,
        Map<String, String> defaultAttributes,
        Integer defaultMaxConcurrentWork
) {
    public WorkerGroupSpec {
        eventBindings = WorkerRequestSupport.copyList(eventBindings);
        defaultAttributes = WorkerRequestSupport.copyStringMap(defaultAttributes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String groupId;
        private List<WorkerEventBindingSpec> eventBindings = WorkerRequestSupport.mutableList();
        private Map<String, String> defaultAttributes = WorkerRequestSupport.mutableMap();
        private Integer defaultMaxConcurrentWork;

        private Builder() {
        }

        public Builder groupId(String groupId) {
            this.groupId = groupId;
            return this;
        }

        public Builder eventBindings(List<WorkerEventBindingSpec> eventBindings) {
            this.eventBindings = eventBindings == null ? WorkerRequestSupport.mutableList() : new java.util.ArrayList<>(eventBindings);
            return this;
        }

        public Builder bindEvent(String eventCode, List<String> projectCodes) {
            this.eventBindings.add(WorkerEventBindingSpec.of(eventCode, projectCodes));
            return this;
        }

        public Builder defaultAttributes(Map<String, String> defaultAttributes) {
            this.defaultAttributes = defaultAttributes == null ? WorkerRequestSupport.mutableMap() : new java.util.LinkedHashMap<>(defaultAttributes);
            return this;
        }

        public Builder defaultAttribute(String key, String value) {
            this.defaultAttributes.put(key, value);
            return this;
        }

        public Builder defaultMaxConcurrentWork(Integer defaultMaxConcurrentWork) {
            this.defaultMaxConcurrentWork = defaultMaxConcurrentWork;
            return this;
        }

        public WorkerGroupSpec build() {
            return new WorkerGroupSpec(groupId, eventBindings, defaultAttributes, defaultMaxConcurrentWork);
        }
    }
}
