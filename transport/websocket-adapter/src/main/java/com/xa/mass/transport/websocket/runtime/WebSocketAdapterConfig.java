package com.xa.mass.transport.websocket.runtime;

import java.util.Objects;

/**
 * Adapter-owned configuration for the bundled embedded WebSocket adapter.
 */
public final class WebSocketAdapterConfig {

    public static final String DEFAULT_ADAPTER_ID = "websocket";
    public static final String PROTOCOL = "websocket";

    private String adapterId = DEFAULT_ADAPTER_ID;
    private boolean enabled = true;
    private boolean serverEnabled = true;
    private int serverPort = 8080;
    private int maxConnections = 1000;
    private String endpointPath = "/ws";

    public WebSocketAdapterConfig() {
    }

    public WebSocketAdapterConfig(WebSocketAdapterConfig source) {
        Objects.requireNonNull(source, "source");
        this.adapterId = source.adapterId;
        this.enabled = source.enabled;
        this.serverEnabled = source.serverEnabled;
        this.serverPort = source.serverPort;
        this.maxConnections = source.maxConnections;
        this.endpointPath = source.endpointPath;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getAdapterId() {
        return adapterId;
    }

    public void setAdapterId(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must not be blank");
        }
        this.adapterId = adapterId.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isServerEnabled() {
        return serverEnabled;
    }

    public void setServerEnabled(boolean serverEnabled) {
        this.serverEnabled = serverEnabled;
    }

    public int getServerPort() {
        return serverPort;
    }

    public void setServerPort(int serverPort) {
        this.serverPort = serverPort;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public String getEndpointPath() {
        return endpointPath;
    }

    public void setEndpointPath(String endpointPath) {
        this.endpointPath = Objects.requireNonNull(endpointPath, "endpointPath");
    }

}
