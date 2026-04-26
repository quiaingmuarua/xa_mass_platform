package com.xa.mass.transport.socket.runtime;

import java.util.Objects;

/**
 * Adapter-owned configuration for the bundled raw-socket realtime adapter.
 */
public final class SocketAdapterConfig {

    private boolean enabled = false;
    private boolean serverEnabled = false;
    private int serverPort = 18089;
    private int maxConnections = 1000;
    private String bindHost = "0.0.0.0";

    public SocketAdapterConfig() {
    }

    public SocketAdapterConfig(SocketAdapterConfig source) {
        Objects.requireNonNull(source, "source");
        this.enabled = source.enabled;
        this.serverEnabled = source.serverEnabled;
        this.serverPort = source.serverPort;
        this.maxConnections = source.maxConnections;
        this.bindHost = source.bindHost;
    }

    public boolean isEnabled() {
        return enabled;
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

    public String getBindHost() {
        return bindHost;
    }

    public void setBindHost(String bindHost) {
        this.bindHost = Objects.requireNonNull(bindHost, "bindHost");
    }
}
