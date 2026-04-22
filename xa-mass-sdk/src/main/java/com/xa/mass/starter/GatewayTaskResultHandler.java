package com.xa.mass.starter;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.gateway.dispatcher.handler.MassMessageHandler;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageAckPayload;
import com.xa.mass.transport.channel.TaskResultIngestChannel;

import java.util.List;
import java.util.Map;

public class GatewayTaskResultHandler implements MassMessageHandler, TaskResultIngestChannel {

    private final TaskManager taskManager;
    private final Gson gson = new Gson();

    public GatewayTaskResultHandler(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public List<MassMessage> handle(MassMessage msg) {
        String taskId = msg.getContext() != null ? msg.getContext().getTaskId() : null;
        String msgId = msg.getMsgId();
        if (taskId == null || msgId == null) {
            return List.of(buildAck(msg, 400, "taskId/msgId are required"));
        }

        TaskResultPayload payload = parsePayload(msg.getPayload());
        boolean handled = ingestTaskResult(
                taskId,
                msgId,
                payload.success,
                payload.detail,
                payload.errorCode,
                payload.output
        );
        int code = handled ? 200 : 404;
        String message = handled ? "task result processed" : "task result ignored";
        return List.of(buildAck(msg, code, message));
    }

    @Override
    public boolean ingestTaskResult(
            String taskId,
            String msgId,
            boolean success,
            String detail,
            String errorCode,
            Map<String, Object> output
    ) {
        return taskManager.handleTaskMessageResult(
                taskId,
                msgId,
                success,
                detail,
                errorCode,
                output
        );
    }

    private TaskResultPayload parsePayload(JsonElement payload) {
        if (payload == null || !payload.isJsonObject()) {
            return new TaskResultPayload(false, "empty payload", null, null);
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
                // Keep the default false result if the payload code is not numeric.
            }
        }

        return new TaskResultPayload(success, detail, errorCode, parseObjectPayload(payload));
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

    private MassMessage buildAck(MassMessage request, int code, String message) {
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

    private static class TaskResultPayload {
        private final boolean success;
        private final String detail;
        private final String errorCode;
        private final Map<String, Object> output;

        private TaskResultPayload(boolean success, String detail, String errorCode, Map<String, Object> output) {
            this.success = success;
            this.detail = detail;
            this.errorCode = errorCode;
            this.output = output;
        }
    }
}
