package com.xa.mass.starter;

import com.xa.mass.gateway.dispatcher.port.ControlEventRequestFrameBridge;
import com.xa.mass.gateway.dispatcher.port.ControlEventResponseFrameSink;

/**
 * Immutable gateway runtime wiring snapshot.
 *
 * <p>These ports are adapter-local construction inputs for the current
 * WebSocket gateway runtime. They are fixed before startup and are not a
 * post-start registration surface.
 */
public record GatewayRuntimePorts(
        ControlEventRequestFrameBridge controlEventRequestFrameBridge,
        ControlEventResponseFrameSink controlEventResponseFrameSink
) {

    public static GatewayRuntimePorts defaults() {
        return new GatewayRuntimePorts(null, new WorkerControlEventResponseHandler());
    }

    public GatewayRuntimePorts withControlEventRequestFrameBridge(
            ControlEventRequestFrameBridge controlEventRequestFrameBridge
    ) {
        return new GatewayRuntimePorts(controlEventRequestFrameBridge, controlEventResponseFrameSink);
    }

    public GatewayRuntimePorts withControlEventResponseFrameSink(
            ControlEventResponseFrameSink controlEventResponseFrameSink
    ) {
        return new GatewayRuntimePorts(controlEventRequestFrameBridge, controlEventResponseFrameSink);
    }
}
