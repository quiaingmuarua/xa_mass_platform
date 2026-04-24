package com.xa.mass.gateway.dispatcher.port;

import com.xa.mass.gateway.model.massMessage.MassMessage;

/**
 * Explicit adapter sink for inbound {@code CONTROL/event} response frames.
 */
@FunctionalInterface
public interface ControlEventResponseFrameSink {

    void handleControlEventResponse(MassMessage frame);
}
