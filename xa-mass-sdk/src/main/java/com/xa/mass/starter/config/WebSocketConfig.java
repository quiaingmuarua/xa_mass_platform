package com.xa.mass.starter.config;

/**
 * @deprecated Prefer {@link TransportConfig}. WebSocket is one transport
 * adapter, not the primary embedded-runtime configuration boundary.
 */
@Deprecated(forRemoval = false)
public class WebSocketConfig extends TransportConfig {

    public WebSocketConfig() {
        super();
    }

    public WebSocketConfig(WebSocketConfig source) {
        super(source);
    }

    public WebSocketConfig(TransportConfig source) {
        super(source);
    }

    @Override
    public WebSocketRuntimeComposition snapshotRuntimeComposition() {
        return new WebSocketRuntimeComposition(this);
    }
}
