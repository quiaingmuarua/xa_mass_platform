package com.xa.mass.sdk.internal;

import com.google.gson.Gson;
import com.xa.mass.sdk.DefaultRuntimeDiagnosticsOperations;
import com.xa.mass.starter.MassApplication;

import java.util.Map;
import java.util.UUID;

public final class DefaultTransportDebugOperations extends DefaultRuntimeDiagnosticsOperations
        implements TransportDebugOperations {

    private static final Gson GSON = new Gson();

    public DefaultTransportDebugOperations(MassApplication delegate) {
        super(delegate);
    }

    @Override
    public Map<String, Object> enqueueRawMessage(Map<String, Object> request) {
        Object workerId = request.get("workerId");
        if (!(workerId instanceof String workerIdText) || workerIdText.isBlank()) {
            return Map.of("success", false, "msg", "workerId is required");
        }
        Object rawJson = request.get("rawJson");
        String payload = rawJson instanceof String rawText ? rawText : GSON.toJson(request);
        boolean accepted = runtimeApplication().sendRawTransportMessage(
                workerIdText.trim(),
                payload,
                UUID.randomUUID().toString()
        );
        if (!accepted) {
            return Map.of("success", false, "msg", "no transport side-channel accepted the worker message");
        }
        return Map.of("success", true, "msg", "message enqueued");
    }
}
