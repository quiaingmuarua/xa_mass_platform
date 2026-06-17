package com.xa.mass.workerpack.sample.client;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.xa.mass.command.model.CommandResponse;
import com.xa.mass.workerpack.sample.command.fixture.SampleClientState;
import com.xa.mass.workerpack.sample.command.fixture.SampleWorkerFaultProfile;
import com.xa.mass.workerpack.sample.command.runtime.SampleCommandRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Adapter-local helper that turns inbound WebSocket task frames into sample
 * worker result frames.
 */
final class SampleWorkerTaskFrameHandler {

    private static final Logger logger = LoggerFactory.getLogger(SampleWorkerTaskFrameHandler.class);
    private static final Gson GSON = new Gson();
    private static final long DEFAULT_TASK_RESPONSE_BASE_DELAY_MS = 15L;
    private static final long DEFAULT_TASK_RESPONSE_JITTER_MS = 35L;

    private final String adapterId;
    private final String transportHint;
    private final String runtimeName;

    SampleWorkerTaskFrameHandler() {
        this("websocket", "realtime", "sample-websocket-client");
    }

    SampleWorkerTaskFrameHandler(String adapterId, String transportHint, String runtimeName) {
        this.adapterId = adapterId == null || adapterId.isBlank() ? "unknown" : adapterId;
        this.transportHint = transportHint == null || transportHint.isBlank() ? "unknown" : transportHint;
        this.runtimeName = runtimeName == null || runtimeName.isBlank() ? "sample-worker-client" : runtimeName;
    }

    TaskResponsePlan prepareResponse(JsonObject taskMessage,
                                     String workerId,
                                     String taskResultStatus,
                                     SampleClientState state) {
        if (taskMessage == null) {
            return null;
        }
        if (isTaskResultFrame(taskMessage)) {
            logger.debug("[{}] Ignoring canonical task result frame for resultCorrelationRef: {}",
                    workerId, resultCorrelationRef(taskMessage));
            return null;
        }
        if (!isTaskDispatchFrame(taskMessage)) {
            return null;
        }
        String resultCorrelationRef = resultCorrelationRef(taskMessage);
        if (resultCorrelationRef == null || resultCorrelationRef.isBlank()) {
            logger.info("[{}] Received task dispatch without resultCorrelationRef, skipping task-result callback",
                    workerId);
            return null;
        }
        String eventCode = readString(taskMessage, "eventCode");
        if (isSampleCommandTask(eventCode)) {
            return prepareSampleCommandTaskResponse(taskMessage, workerId, eventCode);
        }

        String resolvedStatus = resolveTaskResultStatus(taskResultStatus, state);
        int stepCount = 1;
        long delayMillis = resolveTaskResponseDelayMillis(taskMessage, workerId, stepCount, state, resolvedStatus);
        long startedAtEpochMillis = System.currentTimeMillis();
        long finishedAtEpochMillis = startedAtEpochMillis + delayMillis;

        JsonObject response = new JsonObject();
        response.addProperty("resultCorrelationRef", resultCorrelationRef);
        response.addProperty("success", "SUCCESS".equals(resolvedStatus));
        if ("FAILED".equals(resolvedStatus)) {
            response.addProperty("resultCode", "MOCK_TASK_FAILED");
        }

        Map<String, Object> outputMap = new LinkedHashMap<>();
        outputMap.put("detail", "Executed by sample client " + workerId);
        outputMap.put("stepId", resolveStepId(taskMessage));
        outputMap.put("mockData", "Executed by sample client " + workerId);
        outputMap.put("status", resolvedStatus);
        outputMap.put("execution", buildExecutionSnapshot(
                taskMessage,
                stepCount,
                delayMillis,
                startedAtEpochMillis,
                finishedAtEpochMillis,
                resolvedStatus
        ));
        outputMap.put("workerProfile", buildWorkerProfile(workerId));
        response.addProperty("result", GSON.toJson(outputMap));

        if (state != null && state.shouldDropTaskResponse()) {
            logger.info("[{}] Dropped sample task response for resultCorrelationRef={} due to sample state {}",
                    workerId, resultCorrelationRef, state.snapshot());
            return null;
        }
        if (state != null && state.getFaultProfile().shouldStallWithoutResult()) {
            logger.info("[{}] Stalled sample task response for resultCorrelationRef={} due to fault profile {}",
                    workerId, resultCorrelationRef, state.getFaultProfile().toMap());
            return null;
        }
        if (state != null && state.shouldDropFaultProfileResult(
                workerId,
                resultCorrelationRef,
                0
        )) {
            logger.info("[{}] Dropped sample task response for resultCorrelationRef={} due to fault profile {}",
                    workerId, resultCorrelationRef, state.getFaultProfile().toMap());
            return null;
        }
        int duplicateCount = state == null ? 0 : state.getFaultProfile().duplicateResultCount();
        long duplicateGapMillis = state == null ? 0L : state.getFaultProfile().duplicateResultGapMillis();
        if (state != null) {
            applyResultIdentityFault(response, state.getFaultProfile().resultIdentityKind());
            applyMalformedFault(response, state.getFaultProfile().malformedResultKind());
        }
        return new TaskResponsePlan(
                GSON.toJson(response),
                resultCorrelationRef,
                delayMillis,
                null,
                duplicateCount,
                duplicateGapMillis,
                state == null || !state.getFaultProfile().enabled()
                        ? SampleWorkerFaultProfile.DisconnectPhase.NONE
                        : state.getFaultProfile().disconnectPhase()
        );
    }

    private TaskResponsePlan prepareSampleCommandTaskResponse(JsonObject taskMessage,
                                                              String workerId,
                                                              String eventCode) {
        CommandResponse<?> commandResult = dispatchSampleCommandTask(taskMessage, workerId, eventCode);
        boolean success = commandResult != null && commandResult.isSuccess();
        long delayMillis = resolveCommandTaskResponseDelayMillis(taskMessage, workerId);
        long startedAtEpochMillis = System.currentTimeMillis();
        long finishedAtEpochMillis = startedAtEpochMillis + delayMillis;

        JsonObject response = new JsonObject();
        response.addProperty("resultCorrelationRef", resultCorrelationRef(taskMessage));
        response.addProperty("success", success);
        if (!success) {
            response.addProperty("resultCode", resolveCommandTaskErrorCode(commandResult));
        }

        Map<String, Object> outputMap = new LinkedHashMap<>();
        outputMap.put("detail", resolveCommandTaskDetail(eventCode, commandResult));
        outputMap.put("stepId", resolveStepId(taskMessage));
        outputMap.put("eventCode", eventCode);
        outputMap.put("status", success ? "SUCCESS" : "FAILED");
        Map<String, Object> commandData = commandData(commandResult);
        if (!commandData.isEmpty()) {
            outputMap.putAll(commandData);
        }
        outputMap.put("command", buildCommandSnapshot(commandResult));
        outputMap.put("execution", buildExecutionSnapshot(
                taskMessage,
                1,
                delayMillis,
                startedAtEpochMillis,
                finishedAtEpochMillis,
                success ? "SUCCESS" : "FAILED"
        ));
        outputMap.put("workerProfile", buildWorkerProfile(workerId));
        response.addProperty("result", GSON.toJson(outputMap));

        return new TaskResponsePlan(
                GSON.toJson(response),
                resultCorrelationRef(taskMessage),
                delayMillis,
                resolveDisconnectWorkerId(commandResult),
                0,
                0L,
                SampleWorkerFaultProfile.DisconnectPhase.NONE
        );
    }

    private boolean isSampleCommandTask(String eventCode) {
        return eventCode != null && switch (eventCode) {
            case "mock.state.get",
                    "mock.delay.response",
                    "mock.drop.outbound",
                    "mock.task.result.status",
                    "mock.disconnect",
                    "mock.reset",
                    "fault.state.get",
                    "fault.execution.profile",
                    "fault.execution.delay",
                    "fault.execution.stall",
                    "fault.result.drop",
                    "fault.result.duplicate",
                    "fault.result.late",
                    "fault.result.malformed",
                    "fault.result.identity",
                    "fault.transport.disconnect",
                    "fault.worker.state.flap",
                    "fault.reset" -> true;
            default -> false;
        };
    }

    private CommandResponse<?> dispatchSampleCommandTask(JsonObject taskMessage, String workerId, String eventCode) {
        JsonObject commandRequest = new JsonObject();
        commandRequest.addProperty("event", eventCode);
        commandRequest.addProperty("eventCode", eventCode);
        commandRequest.addProperty("workerId", workerId);
        commandRequest.addProperty("resultCorrelationRef", resultCorrelationRef(taskMessage));
        JsonObject input = extractCommandPayload(taskMessage);
        for (Map.Entry<String, JsonElement> entry : input.entrySet()) {
            commandRequest.add(entry.getKey(), entry.getValue().deepCopy());
        }
        return SampleCommandRuntime.dispatch(commandRequest);
    }

    private JsonObject extractCommandPayload(JsonObject taskMessage) {
        JsonObject input = readJsonObject(taskMessage, "input");
        String inputType = readString(input, "type");
        if ("json".equalsIgnoreCase(inputType)) {
            JsonObject data = readJsonObject(input, "data");
            if (!data.entrySet().isEmpty()) {
                return data;
            }
        }
        return input;
    }

    private long resolveCommandTaskResponseDelayMillis(JsonObject taskMessage, String workerId) {
        int stableHash = Objects.hash(workerId, resultCorrelationRef(taskMessage), "sample-command");
        long jitter = Math.floorMod(stableHash, (int) DEFAULT_TASK_RESPONSE_JITTER_MS + 1);
        return DEFAULT_TASK_RESPONSE_BASE_DELAY_MS + jitter;
    }

    private String resolveCommandTaskDetail(String eventCode, CommandResponse<?> commandResult) {
        if (commandResult != null && commandResult.getMessage() != null && !commandResult.getMessage().isBlank()) {
            return commandResult.getMessage();
        }
        return "sample command task executed: " + eventCode;
    }

    private String resolveCommandTaskErrorCode(CommandResponse<?> commandResult) {
        if (commandResult == null) {
            return "MOCK_COMMAND_FAILED";
        }
        return "MOCK_COMMAND_" + Math.max(commandResult.getCode(), 0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> commandData(CommandResponse<?> commandResult) {
        if (commandResult == null || !(commandResult.getData() instanceof Map<?, ?> data) || data.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf((Map<String, Object>) data);
    }

    private Map<String, Object> buildCommandSnapshot(CommandResponse<?> commandResult) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("handled", commandResult != null);
        snapshot.put("status", commandResult == null ? "error" : commandResult.status);
        snapshot.put("code", commandResult == null ? 500 : commandResult.getCode());
        snapshot.put("message", commandResult == null ? "sample command dispatch failed" : commandResult.getMessage());
        snapshot.put("duration", commandResult == null ? 0 : commandResult.duration);
        snapshot.put("env", commandResult == null ? Map.of() : commandResult.env);
        snapshot.put("forward", commandResult == null ? Map.of() : commandResult.forward);
        return snapshot;
    }

    private String resolveDisconnectWorkerId(CommandResponse<?> commandResult) {
        if (commandResult == null || !commandResult.isSuccess() || !(commandResult.getData() instanceof Map<?, ?> data)) {
            return null;
        }
        Object disconnectAfterAck = data.get("disconnectAfterAck");
        if (!Boolean.TRUE.equals(disconnectAfterAck)) {
            return null;
        }
        Object disconnectWorkerId = data.get("disconnectWorkerId");
        return disconnectWorkerId == null ? null : String.valueOf(disconnectWorkerId);
    }

    private String resolveTaskResultStatus(String taskResultStatus, SampleClientState state) {
        if (state == null) {
            return normalizeTaskResultStatus(taskResultStatus);
        }
        return normalizeTaskResultStatus(state.resolveTaskResultStatus(taskResultStatus));
    }

    private long resolveTaskResponseDelayMillis(JsonObject taskMessage,
                                                String workerId,
                                                int stepCount,
                                                SampleClientState state,
                                                String taskStatus) {
        if (state != null && state.getTaskResponseDelayMillis() > 0L) {
            return state.getTaskResponseDelayMillis();
        }
        int stableHash = Objects.hash(workerId, resultCorrelationRef(taskMessage), stepCount);
        long jitter = Math.floorMod(stableHash, (int) DEFAULT_TASK_RESPONSE_JITTER_MS + 1);
        long failurePenalty = "FAILED".equals(taskStatus) ? 10L : 0L;
        long baseDelay = DEFAULT_TASK_RESPONSE_BASE_DELAY_MS
                + jitter
                + Math.max(0, stepCount - 1) * 5L
                + failurePenalty;
        if (state == null || !state.getFaultProfile().enabled()) {
            return baseDelay;
        }
        long faultDelay = state.getFaultProfile().resolveDelayMillis(
                workerId,
                resultCorrelationRef(taskMessage),
                0
        );
        return baseDelay
                + faultDelay
                + state.getFaultProfile().resolveStallDelayMillis()
                + state.getFaultProfile().lateResultDelayMillis();
    }

    boolean isTaskDispatchFrame(JsonObject taskMessage) {
        return taskMessage != null
                && !isTaskResultFrame(taskMessage)
                && readString(taskMessage, "eventCode") != null
                && resultCorrelationRef(taskMessage) != null;
    }

    boolean isTaskResultFrame(JsonObject taskMessage) {
        return taskMessage != null
                && resultCorrelationRef(taskMessage) != null
                && readBoolean(taskMessage, "success");
    }

    private String resolveStepId(JsonObject taskMessage) {
        return firstNonBlank(resultCorrelationRef(taskMessage), "step-0-default");
    }

    private Map<String, Object> buildExecutionSnapshot(JsonObject taskMessage,
                                                       int stepCount,
                                                       long delayMillis,
                                                       long startedAtEpochMillis,
                                                       long finishedAtEpochMillis,
                                                       String taskStatus) {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("transportHint", transportHint);
        execution.put("startedAtEpochMs", startedAtEpochMillis);
        execution.put("finishedAtEpochMs", finishedAtEpochMillis);
        execution.put("startedAt", Instant.ofEpochMilli(startedAtEpochMillis).toString());
        execution.put("finishedAt", Instant.ofEpochMilli(finishedAtEpochMillis).toString());
        execution.put("durationMs", delayMillis);
        execution.put("stepCount", stepCount);
        execution.put("taskStatus", taskStatus);
        execution.put("eventCode", readString(taskMessage, "eventCode"));
        execution.put("resultCorrelationRef", resultCorrelationRef(taskMessage));
        return execution;
    }

    private Map<String, Object> buildWorkerProfile(String workerId) {
        Map<String, Object> workerProfile = new LinkedHashMap<>();
        workerProfile.put("workerId", workerId);
        workerProfile.put("runtime", runtimeName);
        workerProfile.put("host", "sample-host-" + workerId);
        workerProfile.put("os", System.getProperty("os.name"));
        workerProfile.put("javaVersion", System.getProperty("java.version"));
        workerProfile.put("processId", ProcessHandle.current().pid());
        return workerProfile;
    }

    private boolean readBoolean(JsonObject object, String field) {
        return object != null && object.has(field) && !object.get(field).isJsonNull() && object.get(field).getAsBoolean();
    }

    private String readString(JsonObject object, String field) {
        if (object == null || field == null || !object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        try {
            return object.get(field).getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private Integer readInt(JsonObject object, String field) {
        if (object == null || field == null || !object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        try {
            return object.get(field).getAsInt();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String resultCorrelationRef(JsonObject object) {
        return readString(object, "resultCorrelationRef");
    }

    private JsonObject readJsonObject(JsonObject object, String field) {
        if (object == null || field == null || !object.has(field) || object.get(field).isJsonNull()) {
            return new JsonObject();
        }
        JsonElement element = object.get(field);
        return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String normalizeTaskResultStatus(String taskResultStatus) {
        if (taskResultStatus == null || taskResultStatus.isBlank()) {
            return "SUCCESS";
        }
        String normalized = taskResultStatus.trim().toUpperCase();
        return "FAILED".equals(normalized) ? "FAILED" : "SUCCESS";
    }

    private void applyMalformedFault(JsonObject response, SampleWorkerFaultProfile.MalformedResultKind malformedKind) {
        if (malformedKind == null || malformedKind == SampleWorkerFaultProfile.MalformedResultKind.NONE) {
            return;
        }
        switch (malformedKind) {
            case MISSING_CORRELATION_REF -> response.remove("resultCorrelationRef");
            case INVALID_STATUS -> response.add("success", new JsonObject());
            case INVALID_PAYLOAD -> response.addProperty("output", "not-an-object");
            case NONE -> {
            }
        }
    }

    private void applyResultIdentityFault(JsonObject response, SampleWorkerFaultProfile.ResultIdentityKind identityKind) {
        if (identityKind == null || identityKind == SampleWorkerFaultProfile.ResultIdentityKind.NONE) {
            return;
        }
        switch (identityKind) {
            case WRONG_CORRELATION -> response.addProperty("resultCorrelationRef",
                    "wrong-" + readString(response, "resultCorrelationRef"));
            case WRONG_WORKER -> response.addProperty("workerId", "wrong-" + readString(response, "workerId"));
            case WRONG_LEASE -> response.addProperty("leaseId", "wrong-lease");
            case NONE -> {
            }
        }
    }

    record TaskResponsePlan(String responseJson,
                            String resultCorrelationRef,
                            long delayMillis,
                            String disconnectWorkerId,
                            int duplicateCount,
                            long duplicateGapMillis,
                            SampleWorkerFaultProfile.DisconnectPhase disconnectPhase) {
    }
}
