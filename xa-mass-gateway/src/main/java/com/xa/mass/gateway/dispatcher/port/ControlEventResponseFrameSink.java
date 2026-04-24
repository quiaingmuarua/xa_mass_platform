package com.xa.mass.gateway.dispatcher.port;

import com.google.gson.JsonObject;

/**
 * Explicit adapter sink for inbound event-first control response frames.
 */
@FunctionalInterface
public interface ControlEventResponseFrameSink {

    void handleControlEventResponse(String rawJson,
                                    String workerId,
                                    String project,
                                    String messageId,
                                    JsonObject payload);
}
