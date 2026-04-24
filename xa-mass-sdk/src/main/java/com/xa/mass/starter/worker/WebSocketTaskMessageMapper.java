package com.xa.mass.starter.worker;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageAckPayload;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TaskResultReport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WebSocket-only mapper between transport-neutral task models and
 * {@link MassMessage} compatibility frames.
 */
public final class WebSocketTaskMessageMapper {

    private final Gson gson = new Gson();

    public MassMessage toDispatchMessage(TaskDispatchItem item) {
        MassMessage message = new MassMessage();
        message.setMsgId(item.getMsgId());
        message.setMsgType(MessageType.TASK);
        message.setSubMsgType("step");
        message.setFrom(MessageDirection.SERVER);
        message.setProject(item.getProject());
        message.setContext(buildContext(item));
        message.setPayload(gson.toJsonTree(buildPayload(item)));
        return message;
    }

    public TaskResultReport toTaskResultReport(MassMessage msg) {
        String taskId = msg.getContext() != null ? msg.getContext().getTaskId() : null;
        String msgId = msg.getMsgId();
        if (taskId == null || msgId == null) {
            throw new IllegalArgumentException("taskId/msgId are required");
        }

        JsonElement payload = msg.getPayload();
        if (payload == null || !payload.isJsonObject()) {
            return new TaskResultReport(taskId, msgId, false, "empty payload", null, null);
        }

        JsonObject payloadObj = payload.getAsJsonObject();
        String status = readString(payloadObj, "status");
        String errorCode = readString(payloadObj, "errorCode");
        String detail = firstNonBlank(
                readString(payloadObj, "mockData"),
                readString(payloadObj, "message"),
                readString(payloadObj, "errorMessage"),
                payloadObj.toString()
        );

        boolean success = "SUCCESS".equalsIgnoreCase(status);
        if (!success && status == null && payloadObj.has("code") && payloadObj.get("code").isJsonPrimitive()) {
            try {
                int code = payloadObj.get("code").getAsInt();
                success = code >= 200 && code < 300;
            } catch (Exception ignored) {
                // ignore invalid numeric payload code
            }
        }

        return new TaskResultReport(
                taskId,
                msgId,
                success,
                detail,
                errorCode,
                parseObjectPayload(payload)
        );
    }

    public MassMessage buildAck(MassMessage request, int code, String message) {
        MassMessage ack = new MassMessage();
        ack.setMsgId(request.getMsgId());
        ack.setResponse(true);
        ack.setMsgType(MessageType.TASK);
        ack.setSubMsgType(request.getSubMsgType());
        ack.setFrom(MessageDirection.SERVER);
        ack.setProject(request.getProject());
        ack.setContext(request.getContext());
        ack.setPayload(gson.toJsonTree(new MessageAckPayload(code, message)));
        return ack;
    }

    private MessageContext buildContext(TaskDispatchItem item) {
        MessageContext context = new MessageContext();
        context.setWorkerId(item.getWorkerId());
        context.setTaskId(item.getTaskId());
        context.setRetryCount(item.getRetryCount());
        return context;
    }

    private JsonObject buildPayload(TaskDispatchItem item) {
        Map<String, Object> params = new LinkedHashMap<>(item.mergedPayload());
        JsonObject step = new JsonObject();
        step.addProperty("stepId", item.getBatchId() != null ? item.getBatchId() : item.getMsgId());
        step.addProperty("action", "task-dispatch");
        step.add("params", gson.toJsonTree(params));

        JsonArray steps = new JsonArray();
        steps.add(step);
        JsonObject payload = new JsonObject();
        payload.add("steps", steps);
        return payload;
    }

    private String readString(JsonObject payload, String field) {
        if (payload == null || !payload.has(field) || payload.get(field).isJsonNull()) {
            return null;
        }
        try {
            return payload.get(field).getAsString();
        } catch (Exception ex) {
            return payload.get(field).toString();
        }
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

    private Map<String, Object> parseObjectPayload(JsonElement payload) {
        if (payload == null || !payload.isJsonObject()) {
            return null;
        }
        return gson.fromJson(payload, new TypeToken<Map<String, Object>>() {
        }.getType());
    }
}
