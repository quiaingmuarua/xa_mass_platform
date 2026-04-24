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
        if (isTaskResultFrame(taskMessage)) {
            logger.debug("[{}] Ignoring canonical task result frame for messageId: {}", workerId, extractMessageId(taskMessage));
            return null;
        }
        if (!isTaskDispatchFrame(taskMessage)) {
            return null;
        }
        String taskId = readString(taskMessage, "taskId");
        if (taskId == null || taskId.isBlank()) {
            logger.info("[{}] Received task dispatch without taskId, skipping task-result callback. messageId={}",
                    workerId, extractMessageId(taskMessage));
            return null;
        }

        String resolvedStatus = resolveTaskResultStatus(taskResultStatus, state);
        int stepCount = 1;
        long delayMillis = resolveTaskResponseDelayMillis(taskMessage, workerId, stepCount, state, resolvedStatus);
        long startedAtEpochMillis = System.currentTimeMillis();
        long finishedAtEpochMillis = startedAtEpochMillis + delayMillis;

        JsonObject response = new JsonObject();
        response.addProperty("messageId", extractMessageId(taskMessage));
        response.addProperty("workerId", workerId);
        response.addProperty("taskId", taskId);
        String project = readString(taskMessage, "project");
        if (project != null) {
            response.addProperty("project", project);
        }
        Integer retryCount = readInt(taskMessage, "retryCount");
        if (retryCount != null) {
            response.addProperty("retryCount", retryCount);
        }
        response.addProperty("success", "SUCCESS".equals(resolvedStatus));
        response.addProperty("detail", "Executed by mock client " + workerId);
        if ("FAILED".equals(resolvedStatus)) {
            response.addProperty("errorCode", "MOCK_TASK_FAILED");
        }

        Map<String, Object> outputMap = new LinkedHashMap<>();
        outputMap.put("stepId", resolveStepId(taskMessage));
        outputMap.put("mockData", "Executed by mock client " + workerId);
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
        response.add("output", GSON.toJsonTree(outputMap));

        if (state != null && state.shouldDropTaskResponse()) {
            logger.info("[{}] Dropped mock task response for messageId={} due to mock state {}",
                    workerId, extractMessageId(taskMessage), state.snapshot());
            return null;
        }
        return new TaskResponsePlan(GSON.toJson(response), extractMessageId(taskMessage), delayMillis);
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
        int stableHash = Objects.hash(workerId, extractMessageId(taskMessage), readString(taskMessage, "project"), stepCount);
        long jitter = Math.floorMod(stableHash, (int) DEFAULT_TASK_RESPONSE_JITTER_MS + 1);
        long failurePenalty = "FAILED".equals(taskStatus) ? 10L : 0L;
        return DEFAULT_TASK_RESPONSE_BASE_DELAY_MS + jitter + Math.max(0, stepCount - 1) * 5L + failurePenalty;
    }

    boolean isTaskDispatchFrame(JsonObject taskMessage) {
        return taskMessage != null
                && !isTaskResultFrame(taskMessage)
                && readString(taskMessage, "taskId") != null
                && extractMessageId(taskMessage) != null;
    }

    boolean isTaskResultFrame(JsonObject taskMessage) {
        return taskMessage != null
                && readString(taskMessage, "taskId") != null
                && extractMessageId(taskMessage) != null
                && readBoolean(taskMessage, "success");
    }

    private String resolveStepId(JsonObject taskMessage) {
        String batchId = readString(taskMessage, "batchId");
        return batchId != null ? batchId : firstNonBlank(extractMessageId(taskMessage), "step-0-default");
    }

    private Map<String, Object> buildExecutionSnapshot(JsonObject taskMessage,
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
        execution.put("retryCount", readInt(taskMessage, "retryCount") == null ? 0 : readInt(taskMessage, "retryCount"));
        execution.put("project", readString(taskMessage, "project"));
        execution.put("eventCode", readString(taskMessage, "eventCode"));
        execution.put("messageId", extractMessageId(taskMessage));
        execution.put("taskId", readString(taskMessage, "taskId"));
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

    private String extractMessageId(JsonObject object) {
        return readString(object, "messageId");
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

    record TaskResponsePlan(String responseJson, String messageId, long delayMillis) {
    }
}
