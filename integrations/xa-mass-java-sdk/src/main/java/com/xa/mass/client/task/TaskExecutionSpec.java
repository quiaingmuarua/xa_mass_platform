package com.xa.mass.client.task;

public record TaskExecutionSpec(
        String profile,
        String workloadClass,
        Integer batchSize,
        Integer maxRuntimeSeconds,
        Integer defaultMaxRetryCount,
        Boolean foreground
) {
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String profile;
        private String workloadClass;
        private Integer batchSize;
        private Integer maxRuntimeSeconds;
        private Integer defaultMaxRetryCount;
        private Boolean foreground;

        private Builder() {
        }

        public Builder profile(String profile) {
            this.profile = profile;
            return this;
        }

        public Builder workloadClass(String workloadClass) {
            this.workloadClass = workloadClass;
            return this;
        }

        public Builder batchSize(Integer batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Builder maxRuntimeSeconds(Integer maxRuntimeSeconds) {
            this.maxRuntimeSeconds = maxRuntimeSeconds;
            return this;
        }

        public Builder defaultMaxRetryCount(Integer defaultMaxRetryCount) {
            this.defaultMaxRetryCount = defaultMaxRetryCount;
            return this;
        }

        public Builder foreground(Boolean foreground) {
            this.foreground = foreground;
            return this;
        }

        public TaskExecutionSpec build() {
            return new TaskExecutionSpec(profile, workloadClass, batchSize, maxRuntimeSeconds,
                    defaultMaxRetryCount, foreground);
        }
    }
}
