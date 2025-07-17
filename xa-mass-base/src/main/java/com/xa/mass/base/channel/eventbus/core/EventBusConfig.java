package com.xa.mass.base.channel.eventbus.core;

/**
 * EventBus配置类，支持线程池参数调优
 */
public class EventBusConfig {
    
    // 默认配置：基于CPU核心数动态计算
    private int corePoolSize = Math.max(2, Runtime.getRuntime().availableProcessors());
    private int maxPoolSize = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
    private long keepAliveTimeSeconds = 60L;
    private int queueCapacity = 1000;
    private int batchSize = 10;
    private long batchTimeoutMs = 1000L;
    private long handlerTimeoutSeconds = 30L;
    
    // 构造方法
    public EventBusConfig() {}
    
    /**
     * 创建默认配置
     */
    public static EventBusConfig defaultConfig() {
        return new EventBusConfig();
    }
    
    /**
     * 创建高性能配置（适用于高吞吐场景）
     */
    public static EventBusConfig highThroughputConfig() {
        EventBusConfig config = new EventBusConfig();
        config.setCorePoolSize(Runtime.getRuntime().availableProcessors());
        config.setMaxPoolSize(Runtime.getRuntime().availableProcessors() * 4);
        config.setBatchSize(20);
        config.setQueueCapacity(2000);
        return config;
    }
    
    /**
     * 创建低延迟配置（适用于实时性要求高的场景）
     */
    public static EventBusConfig lowLatencyConfig() {
        EventBusConfig config = new EventBusConfig();
        config.setCorePoolSize(Runtime.getRuntime().availableProcessors() * 2);
        config.setMaxPoolSize(Runtime.getRuntime().availableProcessors() * 4);
        config.setBatchSize(5);
        config.setBatchTimeoutMs(100L);
        config.setQueueCapacity(500);
        return config;
    }
    
    // Getters and Setters
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