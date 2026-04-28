package com.xa.mass.base.channel.eventbus.core;

/**
 * Event bus runtime configuration, mainly for thread-pool and batching tuning.
 */
public class EventBusConfig {

    // Default sizing scales with CPU count while keeping a small baseline.
    private int corePoolSize = Math.max(2, Runtime.getRuntime().availableProcessors());
    private int maxPoolSize = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
    private long keepAliveTimeSeconds = 60L;
    private int queueCapacity = 1000;
    private int batchSize = 10;
    private long batchTimeoutMs = 1000L;
    private long handlerTimeoutSeconds = 30L;

    public EventBusConfig() {
    }

    public static EventBusConfig defaultConfig() {
        return new EventBusConfig();
    }

    public static EventBusConfig highThroughputConfig() {
        EventBusConfig config = new EventBusConfig();
        config.setCorePoolSize(Runtime.getRuntime().availableProcessors());
        config.setMaxPoolSize(Runtime.getRuntime().availableProcessors() * 4);
        config.setBatchSize(20);
        config.setQueueCapacity(2000);
        return config;
    }

    public static EventBusConfig lowLatencyConfig() {
        EventBusConfig config = new EventBusConfig();
        config.setCorePoolSize(Runtime.getRuntime().availableProcessors() * 2);
        config.setMaxPoolSize(Runtime.getRuntime().availableProcessors() * 4);
        config.setBatchSize(5);
        config.setBatchTimeoutMs(100L);
        config.setQueueCapacity(500);
        return config;
    }

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public EventBusConfig setCorePoolSize(int corePoolSize) {
        this.corePoolSize = Math.max(1, corePoolSize);
        return this;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public EventBusConfig setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = Math.max(corePoolSize, maxPoolSize);
        return this;
    }

    public long getKeepAliveTimeSeconds() {
        return keepAliveTimeSeconds;
    }

    public EventBusConfig setKeepAliveTimeSeconds(long keepAliveTimeSeconds) {
        this.keepAliveTimeSeconds = Math.max(1L, keepAliveTimeSeconds);
        return this;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public EventBusConfig setQueueCapacity(int queueCapacity) {
        this.queueCapacity = Math.max(10, queueCapacity);
        return this;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public EventBusConfig setBatchSize(int batchSize) {
        this.batchSize = Math.max(1, batchSize);
        return this;
    }

    public long getBatchTimeoutMs() {
        return batchTimeoutMs;
    }

    public EventBusConfig setBatchTimeoutMs(long batchTimeoutMs) {
        this.batchTimeoutMs = Math.max(100L, batchTimeoutMs);
        return this;
    }

    public long getHandlerTimeoutSeconds() {
        return handlerTimeoutSeconds;
    }

    public EventBusConfig setHandlerTimeoutSeconds(long handlerTimeoutSeconds) {
        this.handlerTimeoutSeconds = Math.max(1L, handlerTimeoutSeconds);
        return this;
    }

    @Override
    public String toString() {
        return "EventBusConfig{" +
                "corePoolSize=" + corePoolSize +
                ", maxPoolSize=" + maxPoolSize +
                ", keepAliveTimeSeconds=" + keepAliveTimeSeconds +
                ", queueCapacity=" + queueCapacity +
                ", batchSize=" + batchSize +
                ", batchTimeoutMs=" + batchTimeoutMs +
                ", handlerTimeoutSeconds=" + handlerTimeoutSeconds +
                '}';
    }
}
