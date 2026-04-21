package com.xa.mass.starter;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.gateway.dispatcher.handler.MassMessageHandler;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageAckPayload;

import java.util.List;

public class GatewayTaskResultHandler implements MassMessageHandler {

    private final TaskManager taskManager;
    private final Gson gson = new Gson();

    public GatewayTaskResultHandler(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public List<MassMessage> handle(MassMessage msg) {
        String taskId = msg.getContext() != null ? msg.getContext().getTid() : null;
        String msgId = msg.getMsgId();
        if (taskId == null || msgId == null) {
            return List.of(buildAck(msg, 400, "taskId/msgId are required"));
        }

        TaskResultPayload payload = parsePayload(msg.getPayload());
        boolean handled = taskManager.handleTaskMessageResult(taskId, msgId, payload.success, payload.detail);
        int code = handled ? 200 : 404;
        String message = handled ? "task result processed" : "task result ignored";
        return List.of(buildAck(msg, code, message));
    }

    private TaskResultPayload parsePayload(JsonElement payload) {
        if (payload == null || !payload.isJsonObject()) {
            return new TaskResultPayload(false, "empty payload");
        }

        JsonObject payloadObj = payload.getAsJsonObject();
        String status = readString(payloadObj, "status");
        String detail = firstNonBlank(
                readString(payloadObj, "mockData"),
                readString(payloadObj, "message"),
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

        return new TaskResultPayload(success, detail);
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

        private TaskResultPayload(boolean success, String detail) {
            this.success = success;
            this.detail = detail;
        }
    }
}
