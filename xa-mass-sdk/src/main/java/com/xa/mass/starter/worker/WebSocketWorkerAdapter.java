package com.xa.mass.starter.worker;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.worker.WorkerAdapter;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.dispatcher.handler.MassMessageHandler;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.gateway.model.massMessage.MessageAckPayload;
import com.xa.mass.gateway.model.massMessage.TaskStep;
import com.xa.mass.gateway.model.payload.TaskPayload;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.gateway.session.SessionRoles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WebSocket-backed worker adapter.
 *
 * <p>Bundles the dispatch side (push task messages to connected workers over
 * WebSocket) and the result side (receive task-step callbacks from workers)
 * into a single composable object.
 *
 * <p>To add an HTTP or gRPC adapter, create a parallel class implementing
 * {@link WorkerAdapter} and {@link MassMessageHandler} for that transport.
 */
public class WebSocketWorkerAdapter implements WorkerAdapter, MassMessageHandler {

    public static final String DEFAULT_CONN_ROLE = SessionRoles.TASK_MESSAGES;

    private static final Logger logger = LoggerFactory.getLogger(WebSocketWorkerAdapter.class);

    private final DispatchRuntimeContext dispatchRuntimeContext;
    private final TaskManager taskManager;
    private final Gson gson = new Gson();

    public WebSocketWorkerAdapter(DispatchRuntimeContext dispatchRuntimeContext, TaskManager taskManager) {
        this.dispatchRuntimeContext = dispatchRuntimeContext;
        this.taskManager = taskManager;
    }

    @Override
    public String protocol() {
        return "websocket";
    }

    // Dispatch side.

    @Override
    public void onTaskMsgsReady(Task task, List<TaskMsg> taskMsgs) {
        if (dispatchRuntimeContext == null
                || dispatchRuntimeContext.getMessageTransporter() == null
                || dispatchRuntimeContext.getMessageCodec() == null) {
            logger.warn("Skip task message publishing because dispatcher context or transporter is unavailable");
            return;
        }

        for (TaskMsg taskMsg : taskMsgs) {
            MassMessage message = buildMessage(task, taskMsg);
            String json = dispatchRuntimeContext.getMessageCodec().encode(message);
            Envelope envelope = Envelope.builder()
                    .workerId(taskMsg.getLatestAttemptWorkerId())
                    .connRole(DEFAULT_CONN_ROLE)
                    .project(task.getProject())
                    .traceId(taskMsg.getMsgId())
                    .receivedAt(System.currentTimeMillis())
                    .rawJson(json)
                    .build();
            dispatchRuntimeContext.getMessageTransporter().sendOutput(envelope);
        }
    }

    private MassMessage buildMessage(Task task, TaskMsg taskMsg) {
        MassMessage message = new MassMessage();
        message.setMsgId(taskMsg.getMsgId());
        message.setMsgType(MessageType.TASK);
        message.setSubMsgType("step");
        message.setFrom(MessageDirection.SERVER);
        message.setProject(task.getProject());
        message.setContext(buildContext(task, taskMsg));
        message.setPayload(gson.toJsonTree(buildPayload(task, taskMsg)));
        return message;
    }

    private MessageContext buildContext(Task task, TaskMsg taskMsg) {
        MessageContext context = new MessageContext();
        context.setWorkerId(taskMsg.getLatestAttemptWorkerId());
        context.setConnRole(DEFAULT_CONN_ROLE);
        context.setTaskId(task.getTid());
        context.setRetryCount(taskMsg.getRetryCount());
        return context;
    }

    private TaskPayload buildPayload(Task task, TaskMsg taskMsg) {
        TaskStep step = new TaskStep();
        step.setStepId(taskMsg.getLatestAttemptBatchId() != null ? taskMsg.getLatestAttemptBatchId() : taskMsg.getMsgId());
        step.setAction("task-dispatch");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("taskId", task.getTid());
        params.put("taskName", task.getTaskName());
        params.put("project", task.getProject());
        params.put("userId", task.getUser() != null ? task.getUser().getUserId() : null);
        if (taskMsg.getInput() != null) {
            params.putAll(taskMsg.getInput());
        }
        params.put("workerId", taskMsg.getLatestAttemptWorkerId());
        params.put("workerContextId", taskMsg.getLatestAttemptWorkerContextId());
        params.put("batchId", taskMsg.getLatestAttemptBatchId());
        if (task.getSharedConfig() != null) {
            params.putAll(task.getSharedConfig());
        }
        step.setParams(params);

        TaskPayload payload = new TaskPayload();
        payload.setSteps(List.of(step));
        return payload;
    }

    // Result side.

    @Override
    public List<MassMessage> handle(MassMessage msg) {
        String taskId = msg.getContext() != null ? msg.getContext().getTaskId() : null;
        String msgId = msg.getMsgId();
        if (taskId == null || msgId == null) {
            return List.of(buildAck(msg, 400, "taskId/msgId are required"));
        }

        TaskResultPayload payload = parsePayload(msg.getPayload());
        boolean handled = taskManager.handleTaskMessageResult(
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
