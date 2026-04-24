package com.xa.mass.mock.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.xa.mass.mock.command.mock.MockClientState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Adapter-local helper that turns inbound WebSocket task frames into mock
 * worker result frames.
 */
final class MockWorkerTaskFrameHandler {

    private static final Logger logger = LoggerFactory.getLogger(MockWorkerTaskFrameHandler.class);
    private static final Gson GSON = new Gson();
    private static final long DEFAULT_TASK_RESPONSE_BASE_DELAY_MS = 15L;
    private static final long DEFAULT_TASK_RESPONSE_JITTER_MS = 35L;

    TaskResponsePlan prepareResponse(JsonObject taskMessage,
                                     String workerId,
                                     String taskResultStatus,
                                     MockClientState state) {
        if (taskMessage == null) {
            return null;
        }
        if (readBoolean(taskMessage, "response")) {
            logger.debug("[{}] Ignoring task response frame for msgId: {}", workerId, readString(taskMessage, "msgId"));
            return null;
        }
        JsonObject originalContext = getContext(taskMessage);
        String taskId = readString(originalContext, "taskId");
        if (taskId == null || taskId.isBlank()) {
            logger.info("[{}] Received TASK frame without taskId, skipping task-result callback. msgId={}",
                    workerId, readString(taskMessage, "msgId"));
            return null;
        }

        JsonObject taskPayload = getPayload(taskMessage);
        String resolvedStatus = resolveTaskResultStatus(taskResultStatus, state);
        int stepCount = countSteps(taskPayload);
        long delayMillis = resolveTaskResponseDelayMillis(taskMessage, workerId, stepCount, state, resolvedStatus);
        long startedAtEpochMillis = System.currentTimeMillis();
        long finishedAtEpochMillis = startedAtEpochMillis + delayMillis;

        JsonObject response = new JsonObject();
        response.addProperty("msgId", readString(taskMessage, "msgId"));
        response.addProperty("response", true);
        response.addProperty("msgType", "TASK");
        response.addProperty("from", "CLIENT");
        response.addProperty("subMsgType", "step");
        String project = readString(taskMessage, "project");
        if (project != null) {
            response.addProperty("project", project);
        }

        JsonObject responseContext = new JsonObject();
        responseContext.addProperty("taskId", taskId);
        Integer retryCount = readInt(originalContext, "retryCount");
        if (retryCount != null) {
            responseContext.addProperty("retryCount", retryCount);
        }
        responseContext.addProperty("workerId", workerId);
        response.add("context", responseContext);

        Map<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("stepId", extractFirstStepId(taskPayload));
        payloadMap.put("mockData", "Executed by mock client " + workerId);
        payloadMap.put("status", resolvedStatus);
        payloadMap.put("execution", buildExecutionSnapshot(
                taskMessage,
                originalContext,
                stepCount,
                delayMillis,
                startedAtEpochMillis,
                finishedAtEpochMillis,
                resolvedStatus
        ));
        payloadMap.put("workerProfile", buildWorkerProfile(workerId));
        response.add("payload", GSON.toJsonTree(payloadMap));

        if (state != null && state.shouldDropTaskResponse()) {
            logger.info("[{}] Dropped mock task response for msgId={} due to mock state {}",
                    workerId, readString(response, "msgId"), state.snapshot());
            return null;
        }
        return new TaskResponsePlan(GSON.toJson(response), readString(response, "msgId"), delayMillis);
    }

    private String resolveTaskResultStatus(String taskResultStatus, MockClientState state) {
        if (state == null) {
            return normalizeTaskResultStatus(taskResultStatus);
        }
        return normalizeTaskResultStatus(state.resolveTaskResultStatus(taskResultStatus));
    }

    private long resolveTaskResponseDelayMillis(JsonObject taskMessage,
                                                String workerId,
                                                int stepCount,
                                                MockClientState state,
                                                String taskStatus) {
        if (state != null && state.getTaskResponseDelayMillis() > 0L) {
            return state.getTaskResponseDelayMillis();
        }
        int stableHash = Objects.hash(workerId, readString(taskMessage, "msgId"), readString(taskMessage, "project"), stepCount);
        long jitter = Math.floorMod(stableHash, (int) DEFAULT_TASK_RESPONSE_JITTER_MS + 1);
        long failurePenalty = "FAILED".equals(taskStatus) ? 10L : 0L;
        return DEFAULT_TASK_RESPONSE_BASE_DELAY_MS + jitter + Math.max(0, stepCount - 1) * 5L + failurePenalty;
    }

    private String extractFirstStepId(JsonObject taskPayload) {
        JsonArray steps = taskPayload != null && taskPayload.has("steps") && taskPayload.get("steps").isJsonArray()
                ? taskPayload.getAsJsonArray("steps")
                : null;
        if (steps == null || steps.isEmpty() || !steps.get(0).isJsonObject()) {
            return "step-0-default";
        }
        return readString(steps.get(0).getAsJsonObject(), "stepId") != null
                ? readString(steps.get(0).getAsJsonObject(), "stepId")
                : "step-0-default";
    }

    private int countSteps(JsonObject taskPayload) {
        return taskPayload != null && taskPayload.has("steps") && taskPayload.get("steps").isJsonArray()
                ? taskPayload.getAsJsonArray("steps").size()
                : 0;
    }

    private Map<String, Object> buildExecutionSnapshot(JsonObject taskMessage,
                                                       JsonObject originalContext,
                                                       int stepCount,
                                                       long delayMillis,
                                                       long startedAtEpochMillis,
                                                       long finishedAtEpochMillis,
                                                       String taskStatus) {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("transport", "websocket");
        execution.put("startedAtEpochMs", startedAtEpochMillis);
        execution.put("finishedAtEpochMs", finishedAtEpochMillis);
        execution.put("startedAt", Instant.ofEpochMilli(startedAtEpochMillis).toString());
        execution.put("finishedAt", Instant.ofEpochMilli(finishedAtEpochMillis).toString());
        execution.put("durationMs", delayMillis);
        execution.put("stepCount", stepCount);
        execution.put("taskStatus", taskStatus);
        execution.put("retryCount", readInt(originalContext, "retryCount") == null ? 0 : readInt(originalContext, "retryCount"));
        execution.put("project", readString(taskMessage, "project"));
        execution.put("messageId", readString(taskMessage, "msgId"));
        execution.put("taskId", readString(originalContext, "taskId"));
        return execution;
    }

    private Map<String, Object> buildWorkerProfile(String workerId) {
        Map<String, Object> workerProfile = new LinkedHashMap<>();
        workerProfile.put("workerId", workerId);
        workerProfile.put("runtime", "mock-websocket-client");
        workerProfile.put("host", "mock-host-" + workerId);
        workerProfile.put("os", System.getProperty("os.name"));
        workerProfile.put("javaVersion", System.getProperty("java.version"));
        workerProfile.put("processId", ProcessHandle.current().pid());
        return workerProfile;
    }

    private JsonObject getContext(JsonObject message) {
        return message != null && message.has("context") && message.get("context").isJsonObject()
                ? message.getAsJsonObject("context")
                : new JsonObject();
    }

    private JsonObject getPayload(JsonObject message) {
        return message != null && message.has("payload") && message.get("payload").isJsonObject()
                ? message.getAsJsonObject("payload")
                : new JsonObject();
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

    private String normalizeTaskResultStatus(String taskResultStatus) {
        if (taskResultStatus == null || taskResultStatus.isBlank()) {
            return "SUCCESS";
        }
        String normalized = taskResultStatus.trim().toUpperCase();
        return "FAILED".equals(normalized) ? "FAILED" : "SUCCESS";
    }

    record TaskResponsePlan(String responseJson, String messageId, long delayMillis) {
    }
}
