package com.xa.mass.gateway.dispatcher.port;

import com.xa.mass.transport.model.TaskResultReport;

/**
 * Explicit adapter port for inbound {@code TASK/step} compatibility frames.
 */
@FunctionalInterface
public interface TaskStepFrameBridge {

    boolean handleTaskStep(TaskResultReport report);
}
