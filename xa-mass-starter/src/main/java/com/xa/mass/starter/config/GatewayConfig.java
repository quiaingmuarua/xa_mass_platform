package com.xa.mass.starter.config;

/**
 * 网关配置类
 */
public  class GatewayConfig {
    private boolean enabled = true;
    private int maxConnections = 1000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getMaxConnections() { return maxConnections; }
    public void setMaxConnections(int maxConnections) { this.maxConnections = maxConnections; }
}