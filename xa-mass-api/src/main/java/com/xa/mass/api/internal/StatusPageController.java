package com.xa.mass.api.internal;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.api.model.worker.WorkerSendMessageRequest;
import com.xa.mass.api.model.worker.WorkerSupportedProjectsPageRequest;
import com.xa.mass.base.debug.ManualDebugChatProtocol;
import com.xa.mass.base.debug.WorkerDebugMessageStore;
import com.xa.mass.base.enums.Project;
import com.xa.mass.base.model.Worker;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.gateway.dispatcher.DispatcherContextRegistry;
import com.xa.mass.gateway.dispatcher.context.CodecContext;
import com.xa.mass.gateway.dispatcher.context.SessionContext;
import com.xa.mass.gateway.dispatcher.context.TransportContext;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.gateway.queue.MessageCodec;
import com.xa.mass.gateway.session.ServerSessionManager;
import com.xa.mass.gateway.session.SessionRoles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Controller
@RequestMapping("/status")
public class StatusPageController {
    private static final Gson GSON = new Gson();

    @Autowired
    private WorkerManager workerManager;

    /**
     * Return all supported project codes for worker-edit forms.
     */
    @GetMapping("/workers/allProjects")
    @ResponseBody
    public List<String> getAllProjects() {
        return com.xa.mass.base.enums.Project.getAllCodes();
    }

    /**
     * Replace the supported project list for a worker.
     */
    @PostMapping("/workers/updateSupportedProjects")
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateSupportedProjects(
            @RequestBody WorkerSupportedProjectsPageRequest requestBody) {
        validateKnownFields(requestBody);
        String workerId = readTrimmed(requestBody.getWorkerId());
        List<String> supportedProjects = normalizeSupportedProjects(requestBody.getSupportedProjects());
        Worker worker = workerManager.getWorker(workerId);
        if (worker != null) {
            worker.setSupportedProjects(supportedProjects);
            workerManager.updateWorker(worker);
            return ok(Map.of(
                    "workerId", workerId,
                    "supportedProjects", worker.getSupportedProjects()
            ));
        }
        return notFound("Worker not found");
    }

    @GetMapping("/workers/message-history")
    @ResponseBody
    public ApiResponse<Map<String, Object>> getWorkerMessageHistory(
            @org.springframework.web.bind.annotation.RequestParam String workerId) {
        return ApiResponse.success(Map.of(
                "workerId", workerId,
                "items", WorkerDebugMessageStore.getHistory(workerId)
        ));
    }

    @PostMapping("/workers/send-message")
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<String, Object>>> sendWorkerMessage(
            @RequestBody WorkerSendMessageRequest requestBody) {
        validateKnownFields(requestBody);
        String workerId = readTrimmed(requestBody.getWorkerId());
        if (workerId == null) {
            return badRequest("workerId is required");
        }

        Worker worker = workerManager.getWorker(workerId);
        if (worker == null) {
            return notFound("Worker not found");
        }

        TransportContext transportContext = DispatcherContextRegistry.getTransportContext();
        if (transportContext == null || transportContext.getMessageTransporter() == null) {
            return conflict("Message transporter is not initialized");
        }

        SessionContext sessionContext = DispatcherContextRegistry.getSessionContext();
        if (sessionContext == null || sessionContext.getSessionManager() == null) {
            return conflict("Session manager is not initialized");
        }

        ServerSessionManager sessionManager = sessionContext.getSessionManager();
        if (!sessionManager.isWorkerOnline(workerId, SessionRoles.TASK_MESSAGES)) {
            return conflict("Target worker is offline or task_messages session is unavailable");
        }

        String project;
        try {
            project = resolveProjectCode(requestBody.getProject(), worker);
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        }

        MessageType messageType;
        try {
            messageType = parseMessageType(requestBody.getMsgType());
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        }

        JsonElement payload;
        try {
            payload = toPayloadJson(requestBody.getPayload());
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        }

        String msgId = UUID.randomUUID().toString();
        String subMsgType = resolveSubMsgType(requestBody.getSubMsgType(), messageType);
        payload = normalizePayload(payload, workerId, msgId, messageType, subMsgType);

        MassMessage message = new MassMessage();
        message.setMsgId(msgId);
        message.setMsgType(messageType);
        message.setSubMsgType(subMsgType);
        message.setFrom(MessageDirection.SERVER);
        message.setProject(project);
        message.setContext(buildMessageContext(workerId));
        message.setPayload(payload);

        String rawJson = encodeMessage(message);
        Envelope envelope = Envelope.builder()
                .workerId(workerId)
                .connRole(SessionRoles.TASK_MESSAGES)
                .project(project)
                .traceId(msgId)
                .receivedAt(System.currentTimeMillis())
                .rawJson(rawJson)
                .build();
        WorkerDebugMessageStore.recordOutbound(
                workerId,
                project,
                messageType.name(),
                subMsgType,
                msgId,
                GSON.toJson(payload),
                rawJson,
                "message queued to dispatcher"
        );
        transportContext.getMessageTransporter().sendOutput(envelope);

        return ok(Map.of(
                "messageId", msgId,
                "workerId", workerId,
                "project", project,
                "msgType", messageType.name(),
                "subMsgType", subMsgType
        ));
    }

    private void validateKnownFields(WorkerSupportedProjectsPageRequest requestBody) {
        if (requestBody == null) {
            throw new IllegalArgumentException("worker request body is required");
        }
        if (requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported worker page update fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
        }
        if (readTrimmed(requestBody.getWorkerId()) == null) {
            throw new IllegalArgumentException("workerId is required");
        }
        if (requestBody.getSupportedProjects() == null) {
            throw new IllegalArgumentException("supportedProjects is required");
        }
    }

    private void validateKnownFields(WorkerSendMessageRequest requestBody) {
        if (requestBody == null) {
            throw new IllegalArgumentException("worker message request body is required");
        }
        if (requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported worker message fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
        }
    }

    private List<String> normalizeSupportedProjects(List<String> supportedProjects) {
        return supportedProjects.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> Project.requireCode(value).getCode())
                .distinct()
                .toList();
    }

    private MessageContext buildMessageContext(String workerId) {
        MessageContext context = new MessageContext();
        context.setWorkerId(workerId);
        context.setConnRole(SessionRoles.TASK_MESSAGES);
        return context;
    }

    private String encodeMessage(MassMessage message) {
        CodecContext codecContext = DispatcherContextRegistry.getCodecContext();
        MessageCodec codec = codecContext != null ? codecContext.getMessageCodec() : null;
        return codec != null ? codec.encode(message) : GSON.toJson(message);
    }

    private JsonElement toPayloadJson(Object payloadObj) {
        if (payloadObj == null) {
            return GSON.toJsonTree(Map.of());
        }
        if (payloadObj instanceof String payloadText) {
            String trimmed = payloadText.trim();
            if (trimmed.isEmpty()) {
                return GSON.toJsonTree(Map.of());
            }
            try {
                return GSON.fromJson(trimmed, JsonElement.class);
            } catch (JsonSyntaxException ex) {
                throw new IllegalArgumentException("payload must be valid JSON");
            }
        }
        return GSON.toJsonTree(payloadObj);
    }

    private MessageType parseMessageType(Object messageTypeObj) {
        String text = defaultIfBlank(readTrimmed(messageTypeObj), MessageType.TASK.name());
        try {
            return MessageType.valueOf(text.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported msgType: " + text);
        }
    }

    private String resolveSubMsgType(Object subMsgTypeObj, MessageType messageType) {
        String explicit = readTrimmed(subMsgTypeObj);
        if (explicit != null) {
            return explicit;
        }
        if (messageType == MessageType.CONTROL) {
            return ManualDebugChatProtocol.SUB_MSG_TYPE;
        }
        return "manual";
    }

    private JsonElement normalizePayload(JsonElement payload,
                                         String workerId,
                                         String messageId,
                                         MessageType messageType,
                                         String subMsgType) {
        if (messageType == MessageType.CONTROL && ManualDebugChatProtocol.SUB_MSG_TYPE.equals(subMsgType)) {
            JsonObject normalized = payload != null && payload.isJsonObject()
                    ? payload.getAsJsonObject().deepCopy()
                    : new JsonObject();
            putIfMissing(normalized, ManualDebugChatProtocol.MESSAGE_KIND_FIELD, ManualDebugChatProtocol.MESSAGE_KIND_REQUEST);
            putIfMissing(normalized, ManualDebugChatProtocol.WORKER_ID_FIELD, workerId);
            putIfMissing(normalized, ManualDebugChatProtocol.SENT_AT_FIELD, System.currentTimeMillis());
            putIfMissing(normalized, ManualDebugChatProtocol.EXPECT_REPLY_FIELD, true);
            putIfMissing(normalized, "clientMessageId", messageId);
            putIfMissing(normalized, ManualDebugChatProtocol.TEXT_FIELD, "");
            return normalized;
        }
        return payload;
    }

    private void putIfMissing(JsonObject payload, String field, String value) {
        if (!payload.has(field) || payload.get(field).isJsonNull()) {
            payload.addProperty(field, value);
        }
    }

    private void putIfMissing(JsonObject payload, String field, Number value) {
        if (!payload.has(field) || payload.get(field).isJsonNull()) {
            payload.addProperty(field, value);
        }
    }

    private void putIfMissing(JsonObject payload, String field, Boolean value) {
        if (!payload.has(field) || payload.get(field).isJsonNull()) {
            payload.addProperty(field, value);
        }
    }

    private String resolveProjectCode(Object projectObj, Worker worker) {
        String requestedProject = readTrimmed(projectObj);
        if (requestedProject != null) {
            return Project.requireCode(requestedProject).getCode();
        }
        List<String> supportedProjects = worker.getSupportedProjects();
        if (supportedProjects != null && !supportedProjects.isEmpty()) {
            return Project.requireCode(supportedProjects.get(0)).getCode();
        }
        return Project.DEMO_APP.getCode();
    }

    private String readTrimmed(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> ok(Map<String, ?> data) {
        return ResponseEntity.ok(ApiResponse.success(new LinkedHashMap<>(data)));
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> badRequest(String message) {
        return ResponseEntity.badRequest().body(ApiResponse.error(400, message));
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> conflict(String message) {
        return ResponseEntity.status(409).body(ApiResponse.error(409, message));
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> notFound(String message) {
        return ResponseEntity.status(404).body(ApiResponse.error(404, message));
    }
}
