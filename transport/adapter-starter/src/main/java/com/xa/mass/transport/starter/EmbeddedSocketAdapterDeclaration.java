package com.xa.mass.transport.starter;

import java.util.Locale;
import java.util.Objects;

/**
 * Adapter-starter-owned declaration for the bundled embedded socket adapter.
 */
public final class EmbeddedSocketAdapterDeclaration {

    public static final String DEFAULT_ADAPTER_ID = "socket";
    public static final int DEFAULT_SERVER_PORT = 18089;
    public static final int DEFAULT_MAX_CONNECTIONS = 1000;
    public static final String DEFAULT_BIND_HOST = "0.0.0.0";

    private String adapterId = DEFAULT_ADAPTER_ID;
    private boolean enabled;
    private boolean serverEnabled;
    private int serverPort = DEFAULT_SERVER_PORT;
    private int maxConnections = DEFAULT_MAX_CONNECTIONS;
    private String bindHost = DEFAULT_BIND_HOST;

    public EmbeddedSocketAdapterDeclaration() {
    }

    public EmbeddedSocketAdapterDeclaration(EmbeddedSocketAdapterDeclaration source) {
        Objects.requireNonNull(source, "source");
        this.adapterId = source.adapterId;
        this.enabled = source.enabled;
        this.serverEnabled = source.serverEnabled;
        this.serverPort = source.serverPort;
        this.maxConnections = source.maxConnections;
        this.bindHost = source.bindHost;
    }

    public String adapterId() {
        return adapterId;
    }

    public String getAdapterId() {
        return adapterId();
    }

    public void adapterId(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must not be blank");
        }
        this.adapterId = adapterId.trim().toLowerCase(Locale.ROOT);
    }

    public void setAdapterId(String adapterId) {
        adapterId(adapterId);
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean isEnabled() {
        return enabled();
    }

    public void enabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setEnabled(boolean enabled) {
        enabled(enabled);
    }

    public boolean serverEnabled() {
        return serverEnabled;
    }

    public boolean isServerEnabled() {
        return serverEnabled();
    }

    public void serverEnabled(boolean serverEnabled) {
        this.serverEnabled = serverEnabled;
    }

    public void setServerEnabled(boolean serverEnabled) {
        serverEnabled(serverEnabled);
    }

    public int serverPort() {
        return serverPort;
    }

    public int getServerPort() {
        return serverPort();
    }

    public void serverPort(int serverPort) {
        this.serverPort = serverPort;
    }

    public void setServerPort(int serverPort) {
        serverPort(serverPort);
    }

    public int maxConnections() {
        return maxConnections;
    }

    public int getMaxConnections() {
        return maxConnections();
    }

    public void maxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        maxConnections(maxConnections);
    }

    public String bindHost() {
        return bindHost;
    }

    public String getBindHost() {
        return bindHost();
    }

    public void bindHost(String bindHost) {
        this.bindHost = Objects.requireNonNull(bindHost, "bindHost");
    }

    public void setBindHost(String bindHost) {
        bindHost(bindHost);
    }
}
