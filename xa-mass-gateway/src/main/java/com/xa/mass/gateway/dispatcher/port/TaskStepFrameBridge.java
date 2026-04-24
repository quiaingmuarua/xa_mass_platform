package com.xa.mass.gateway.dispatcher.port;

import com.xa.mass.gateway.model.massMessage.MassMessage;

import java.util.List;

/**
 * Explicit adapter port for inbound {@code TASK/step} compatibility frames.
 */
@FunctionalInterface
public interface TaskStepFrameBridge {

    List<MassMessage> handleTaskStep(MassMessage frame);
}
