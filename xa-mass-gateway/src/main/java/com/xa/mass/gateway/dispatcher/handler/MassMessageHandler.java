package com.xa.mass.gateway.dispatcher.handler;


import com.xa.mass.gateway.model.massMessage.MassMessage;

import java.util.List;

/**
 * Handler for WebSocket compatibility frames.
 *
 * <p>This interface sits at the gateway codec boundary. Implementations should
 * translate {@link MassMessage} into transport-neutral runtime models as early
 * as possible instead of carrying tuple semantics deeper into engine or SDK
 * logic.
 */
@Deprecated(forRemoval = false)
@FunctionalInterface
public interface MassMessageHandler {
    /**
     * Handles a decoded compatibility frame and returns zero or more response
     * frames to emit back through the same transport.
     */
    List<MassMessage> handle(MassMessage msg);
}
