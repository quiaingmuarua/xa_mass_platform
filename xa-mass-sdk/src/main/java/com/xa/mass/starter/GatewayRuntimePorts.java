package com.xa.mass.starter;

import com.xa.mass.gateway.dispatcher.port.ControlEventRequestHandler;
import com.xa.mass.gateway.dispatcher.port.ControlEventResponseFrameSink;

/**
 * Immutable gateway runtime wiring snapshot.
 *
 * <p>These ports are adapter-local construction inputs for the current
 * WebSocket gateway runtime. They are fixed before startup and are not a
 * post-start registration surface.
 */
public record GatewayRuntimePorts(
        ControlEventRequestHandler controlEventRequestHandler,
        ControlEventResponseFrameSink controlEventResponseFrameSink
) {

    public static GatewayRuntimePorts defaults() {
        return new GatewayRuntimePorts(null, new WorkerControlEventResponseHandler());
    }

    public GatewayRuntimePorts withControlEventRequestHandler(
            ControlEventRequestHandler controlEventRequestHandler
    ) {
        return new GatewayRuntimePorts(controlEventRequestHandler, controlEventResponseFrameSink);
    }

    public GatewayRuntimePorts withControlEventResponseFrameSink(
            ControlEventResponseFrameSink controlEventResponseFrameSink
    ) {
        return new GatewayRuntimePorts(controlEventRequestHandler, controlEventResponseFrameSink);
    }
}
