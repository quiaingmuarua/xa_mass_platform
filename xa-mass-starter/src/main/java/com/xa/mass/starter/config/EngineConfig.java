package com.xa.mass.starter.config;

/**
 * 引擎配置类
 */
public  class EngineConfig {
    private boolean enabled = true;
    private int workerThreads = 8;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }
}