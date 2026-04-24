package com.xa.mass.gateway.dispatcher.port;

import com.xa.mass.gateway.model.massMessage.MassMessage;

import java.util.List;

/**
 * Explicit adapter port for inbound {@code CONTROL/event} request frames.
 */
@FunctionalInterface
public interface ControlEventRequestFrameBridge {

    List<MassMessage> handleControlEventRequest(MassMessage frame);
}
