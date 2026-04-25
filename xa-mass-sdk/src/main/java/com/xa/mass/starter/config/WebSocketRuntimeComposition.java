package com.xa.mass.starter.config;

/**
 * @deprecated Prefer {@link TransportRuntimeComposition}. WebSocket is one
 * transport adapter, not the primary runtime-composition boundary.
 */
@Deprecated(forRemoval = false)
public final class WebSocketRuntimeComposition extends TransportRuntimeComposition {

    public WebSocketRuntimeComposition(WebSocketConfig source) {
        super(source);
    }

    public WebSocketRuntimeComposition(TransportConfig source) {
        super(source);
    }
}
