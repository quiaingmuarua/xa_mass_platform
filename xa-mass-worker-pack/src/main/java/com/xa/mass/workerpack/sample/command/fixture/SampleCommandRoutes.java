package com.xa.mass.workerpack.sample.command.fixture;

import com.google.gson.JsonObject;
import com.xa.mass.base.exception.CommandException;
import com.xa.mass.base.exception.ErrorCode;
import com.xa.mass.command.core.CommandDefinition;
import com.xa.mass.command.core.CommandRegistry;
import com.xa.mass.command.model.CommandContext;
import com.xa.mass.workerpack.sample.client.ClientSessionManager;
import com.xa.mass.workerpack.sample.client.SampleWorkerClient;

import java.util.LinkedHashMap;
import java.util.Map;

public final class SampleCommandRoutes {

    private SampleCommandRoutes() {
    }

    public static void registerSampleRoutes() {
        registerIfAbsent(CommandDefinition.<JsonObject, Map<String, Object>>builder("mock.state.get")
                .handler(SampleCommandRoutes::sampleStateGet)
                .resolver(json -> json)
                .summary("Return the current mock fault-injection state for a worker.")
                .suggestedPhases("prepare", "verify")
                .safeForScenario(true)
                .build());

        registerIfAbsent(CommandDefinition.<JsonObject, Map<String, Object>>builder("mock.delay.response")
                .handler(SampleCommandRoutes::sampleDelayResponse)
                .resolver(json -> json)
                .summary("Delay future TASK result responses from the worker by a bounded number of milliseconds.")
                .suggestedPhases("prepare", "trigger", "verify")
                .safeForScenario(true)
                .build());

        registerIfAbsent(CommandDefinition.<JsonObject, Map<String, Object>>builder("mock.drop.outbound")
                .handler(SampleCommandRoutes::sampleDropOutbound)
                .resolver(json -> json)
                .summary("Drop future TASK result responses using mode off/once/always.")
                .suggestedPhases("prepare", "trigger", "verify")
                .safeForScenario(true)
                .build());

        registerIfAbsent(CommandDefinition.<JsonObject, Map<String, Object>>builder("mock.task.result.status")
                .handler(SampleCommandRoutes::sampleTaskResultStatus)
                .resolver(json -> json)
                .summary("Override future TASK result status with SUCCESS/FAILED, or clear the override.")
                .suggestedPhases("prepare", "trigger", "verify")
                .safeForScenario(true)
                .build());

        registerIfAbsent(CommandDefinition.<JsonObject, Map<String, Object>>builder("mock.disconnect")
                .handler(SampleCommandRoutes::sampleDisconnect)
                .resolver(json -> json)
                .summary("Disconnect the target worker after the current disconnect task result is sent.")
                .suggestedPhases("trigger", "verify")
                .safeForScenario(true)
                .build());

        registerIfAbsent(CommandDefinition.<JsonObject, Map<String, Object>>builder("mock.reset")
                .handler(SampleCommandRoutes::sampleReset)
                .resolver(json -> json)
                .summary("Reset all mock fault-injection state for the target worker.")
                .suggestedPhases("prepare", "verify")
                .safeForScenario(true)
                .build());
    }

    private static Map<String, Object> sampleStateGet(JsonObject request, CommandContext context) {
        String workerId = requireWorkerId(request);
        return buildStateResponse(workerId, stateRegistry(context).getOrCreate(workerId), "state");
    }

    private static Map<String, Object> sampleDelayResponse(JsonObject request, CommandContext context) {
        String workerId = requireWorkerId(request);
        long millis = boundedLong(request, "millis", 0L, 30_000L);
        SampleClientState state = stateRegistry(context).getOrCreate(workerId);
        state.setTaskResponseDelayMillis(millis);
        return buildStateResponse(workerId, state, "delay_updated");
    }

    private static Map<String, Object> sampleDropOutbound(JsonObject request, CommandContext context) {
        String workerId = requireWorkerId(request);
        String modeValue = stringValue(request, "mode", "OFF");
        SampleClientState.DropMode dropMode;
        try {
            dropMode = SampleClientState.DropMode.fromValue(modeValue);
        } catch (IllegalArgumentException e) {
            throw new CommandException(ErrorCode.PARSE_ERROR, e.getMessage());
        }
        SampleClientState state = stateRegistry(context).getOrCreate(workerId);
        state.setTaskResponseDropMode(dropMode);
        return buildStateResponse(workerId, state, "drop_mode_updated");
    }

    private static Map<String, Object> sampleTaskResultStatus(JsonObject request, CommandContext context) {
        String workerId = requireWorkerId(request);
        String status = stringValue(request, "status", "");
        if (!status.isBlank()) {
            String normalized = status.trim().toUpperCase();
            if (!"SUCCESS".equals(normalized) && !"FAILED".equals(normalized)) {
                throw new CommandException(ErrorCode.PARSE_ERROR, "sample task result status must be SUCCESS, FAILED, or blank");
            }
            status = normalized;
        }
        SampleClientState state = stateRegistry(context).getOrCreate(workerId);
        state.setTaskResultStatusOverride(status);
        return buildStateResponse(workerId, state, "task_result_status_updated");
    }

    private static Map<String, Object> sampleDisconnect(JsonObject request, CommandContext context) {
        String workerId = requireWorkerId(request);
        SampleWorkerClient client = clientManager(context).getClient(workerId);
        if (client == null) {
            throw new CommandException(ErrorCode.INIT_ERROR, "sample client not found: " + workerId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workerId", workerId);
        result.put("action", "disconnect");
        result.put("disconnectAfterAck", true);
        result.put("disconnectWorkerId", workerId);
        result.put("connected", client.isConnected());
        result.put("state", stateRegistry(context).getOrCreate(workerId).snapshot());
        return result;
    }

    private static Map<String, Object> sampleReset(JsonObject request, CommandContext context) {
        String workerId = requireWorkerId(request);
        SampleClientState state = stateRegistry(context).getOrCreate(workerId);
        state.reset();
        return buildStateResponse(workerId, state, "reset");
    }

    private static Map<String, Object> buildStateResponse(String workerId, SampleClientState state, String action) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workerId", workerId);
        result.put("action", action);
        result.put("state", state.snapshot());
        return result;
    }

    private static SampleClientStateRegistry stateRegistry(CommandContext context) {
        return context.require(SampleClientStateRegistry.class);
    }

    private static ClientSessionManager clientManager(CommandContext context) {
        return context.require(ClientSessionManager.class);
    }

    private static String requireWorkerId(JsonObject request) {
        String workerId = stringValue(request, "workerId", "");
        if (workerId.isBlank()) {
            throw new CommandException(ErrorCode.PARSE_ERROR, "workerId is required");
        }
        return workerId;
    }

    private static String stringValue(JsonObject json, String field, String defaultValue) {
        if (json == null || !json.has(field) || json.get(field).isJsonNull()) {
            return defaultValue;
        }
        return json.get(field).getAsString();
    }

    private static long boundedLong(JsonObject json, String field, long defaultValue, long maxValue) {
        if (json == null || !json.has(field) || json.get(field).isJsonNull()) {
            return defaultValue;
        }
        try {
            long value = json.get(field).getAsLong();
            return Math.max(0L, Math.min(value, maxValue));
        } catch (Exception e) {
            throw new CommandException(ErrorCode.PARSE_ERROR, field + " must be a number");
        }
    }

    private static void registerIfAbsent(CommandDefinition<?, ?> definition) {
        if (!CommandRegistry.contains(definition.getEvent())) {
            CommandRegistry.register(definition);
        }
    }
}

