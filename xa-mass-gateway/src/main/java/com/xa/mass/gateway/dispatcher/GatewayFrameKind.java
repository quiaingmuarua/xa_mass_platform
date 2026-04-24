package com.xa.mass.gateway.dispatcher;

/**
 * Fixed compatibility frame kinds understood by the current WebSocket adapter.
 *
 * <p>These kinds classify adapter-local wire frames only. They are not a
 * platform capability model and must not grow into a second runtime routing
 * surface alongside global {@code eventCode}.
 */
public enum GatewayFrameKind {
    PING_HEARTBEAT,
    PONG_HEARTBEAT,
    TASK_STEP,
    CONTROL_EVENT_REQUEST,
    CONTROL_EVENT_RESPONSE,
    UNKNOWN
}
