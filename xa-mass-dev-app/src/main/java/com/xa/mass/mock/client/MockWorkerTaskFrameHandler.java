package com.xa.mass.mock.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.mock.command.mock.MockClientState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
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

    TaskResponsePlan prepareResponse(MassMessage taskMessage,
                                     String workerId,
                                     String taskResultStatus,
                                     MockClientState state) {
        if (taskMessage == null) {
            return null;
        }
        if (taskMessage.isResponse()) {
            logger.debug("[{}] Ignoring task response frame for msgId: {}", workerId, taskMessage.getMsgId());
            return null;
        }
        MessageContext originalContext = taskMessage.getContext();
        if (originalContext == null || originalContext.getTaskId() == null || originalContext.getTaskId().isBlank()) {
            logger.info("[{}] Received TASK frame without taskId, skipping task-result callback. msgId={}",
                    workerId, taskMessage.getMsgId());
            return null;
        }

        JsonObject taskPayload = taskMessage.getPayload() != null && taskMessage.getPayload().isJsonObject()
                ? taskMessage.getPayload().getAsJsonObject()
                : null;
        String resolvedStatus = resolveTaskResultStatus(taskResultStatus, state);
        int stepCount = countSteps(taskPayload);
        long delayMillis = resolveTaskResponseDelayMillis(taskMessage, workerId, stepCount, state, resolvedStatus);
        long startedAtEpochMillis = System.currentTimeMillis();
        long finishedAtEpochMillis = startedAtEpochMillis + delayMillis;

        MassMessage response = new MassMessage();
        response.setMsgId(taskMessage.getMsgId());
        response.setResponse(true);
        response.setMsgType(MessageType.TASK);
        response.setFrom(MessageDirection.CLIENT);
        response.setSubMsgType("step");
        response.setProject(taskMessage.getProject());

        MessageContext responseContext = new MessageContext();
        responseContext.setConnRole(originalContext.getConnRole());
        responseContext.setTaskId(originalContext.getTaskId());
        responseContext.setRetryCount(originalContext.getRetryCount());
        responseContext.setWorkerId(workerId);
        response.setContext(responseContext);

        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("stepId", extractFirstStepId(taskPayload));
        payloadMap.put("mockData", "Executed by mock client " + workerId);
        payloadMap.put("status", resolvedStatus);
        payloadMap.put("execution", buildExecutionSnapshot(
                originalContext,
                taskMessage,
                stepCount,
                delayMillis,
                startedAtEpochMillis,
                finishedAtEpochMillis,
                resolvedStatus
        ));
        payloadMap.put("workerProfile", buildWorkerProfile(workerId));
        response.setPayload(GSON.toJsonTree(payloadMap));

        if (state != null && state.shouldDropTaskResponse()) {
            logger.info("[{}] Dropped mock task response for msgId={} due to mock state {}",
                    workerId, response.getMsgId(), state.snapshot());
            return null;
        }
        return new TaskResponsePlan(response, delayMillis);
    }

    private String resolveTaskResultStatus(String taskResultStatus, MockClientState state) {
        if (state == null) {
            return normalizeTaskResultStatus(taskResultStatus);
        }
        return normalizeTaskResultStatus(state.resolveTaskResultStatus(taskResultStatus));
    }

    private long resolveTaskResponseDelayMillis(MassMessage taskMessage,
                                                String workerId,
                                                int stepCount,
                                                MockClientState state,
                                                String taskStatus) {
        if (state != null && state.getTaskResponseDelayMillis() > 0L) {
            return state.getTaskResponseDelayMillis();
        }
        int stableHash = Objects.hash(workerId, taskMessage.getMsgId(), taskMessage.getProject(), stepCount);
        long jitter = Math.floorMod(stableHash, (int) DEFAULT_TASK_RESPONSE_JITTER_MS + 1);
        long failurePenalty = "FAILED".equals(taskStatus) ? 10L : 0L;
        return DEFAULT_TASK_RESPONSE_BASE_DELAY_MS + jitter + Math.max(0, stepCount - 1) * 5L + failurePenalty;
    }

    private String extractFirstStepId(JsonObject taskPayload) {
        if (taskPayload == null || !taskPayload.has("steps") || !taskPayload.get("steps").isJsonArray()) {
            return "step-0-default";
        }
        var steps = taskPayload.getAsJsonArray("steps");
        if (steps.isEmpty() || !steps.get(0).isJsonObject()) {
            return "step-0-default";
        }
        JsonObject firstStep = steps.get(0).getAsJsonObject();
        if (!firstStep.has("stepId") || firstStep.get("stepId").isJsonNull()) {
            return "step-0-default";
        }
        return firstStep.get("stepId").getAsString();
    }

    private int countSteps(JsonObject taskPayload) {
        if (taskPayload == null || !taskPayload.has("steps") || !taskPayload.get("steps").isJsonArray()) {
            return 0;
        }
        return taskPayload.getAsJsonArray("steps").size();
    }

    private Map<String, Object> buildExecutionSnapshot(MessageContext originalContext,
                                                       MassMessage taskMessage,
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
        Integer retryCount = originalContext != null ? originalContext.getRetryCount() : null;
        execution.put("retryCount", retryCount == null ? 0 : retryCount);
        execution.put("project", taskMessage.getProject());
        execution.put("messageId", taskMessage.getMsgId());
        execution.put("taskId", originalContext != null ? originalContext.getTaskId() : null);
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

    private String normalizeTaskResultStatus(String taskResultStatus) {
        if (taskResultStatus == null || taskResultStatus.isBlank()) {
            return "SUCCESS";
        }
        String normalized = taskResultStatus.trim().toUpperCase();
        return "FAILED".equals(normalized) ? "FAILED" : "SUCCESS";
    }

    record TaskResponsePlan(MassMessage response, long delayMillis) {
    }
}
