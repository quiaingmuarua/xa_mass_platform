package com.xa.mass.starter.worker;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;
import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.base.debug.WorkerDebugMessageStore;
import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.gateway.queue.MessageCodec;
import com.xa.mass.starter.transport.WorkerControlEventDispatch;
import com.xa.mass.starter.transport.WorkerControlEventPublishResult;
import com.xa.mass.starter.transport.WorkerControlEventPublisher;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.WorkerEndpointRoles;

import java.util.Map;
import java.util.UUID;

/**
 * WebSocket adapter-local publisher for outbound worker control/debug events.
 */
public final class WebSocketWorkerControlEventPublisher implements WorkerControlEventPublisher {

    private static final Gson GSON = new Gson();

    private final MessageTransporter<Envelope> messageTransporter;
    private final WorkerEndpointRegistry endpointRegistry;
    private final MessageCodec messageCodec;

    public WebSocketWorkerControlEventPublisher(MessageTransporter<Envelope> messageTransporter,
                                                WorkerEndpointRegistry endpointRegistry,
                                                MessageCodec messageCodec) {
        this.messageTransporter = messageTransporter;
        this.endpointRegistry = endpointRegistry;
        this.messageCodec = messageCodec;
    }

    @Override
    public WorkerControlEventPublishResult publish(WorkerControlEventDispatch request) {
        if (messageTransporter == null) {
            throw new IllegalStateException("Message transporter is not initialized");
        }
        if (endpointRegistry == null) {
            throw new IllegalStateException("Session manager is not initialized");
        }

        if (!endpointRegistry.isWorkerOnline(request.getWorkerId(), WorkerEndpointRoles.TASK_DISPATCH)) {
            throw new IllegalStateException("Target worker is offline or task dispatch endpoint is unavailable");
        }

        JsonElement payloadJson = toPayloadJson(request.getPayload());
        String messageId = UUID.randomUUID().toString();

        MassMessage message = new MassMessage();
        message.setMsgId(messageId);
        message.setMsgType(MessageType.CONTROL);
        message.setSubMsgType(WorkerControlEventProtocol.SUB_MSG_TYPE);
        message.setFrom(MessageDirection.SERVER);
        message.setProject(request.getProject());
        message.setContext(buildMessageContext(request.getWorkerId()));
        message.setPayload(payloadJson);

        String rawJson = encodeMessage(message);
        Envelope envelope = Envelope.builder()
                .workerId(request.getWorkerId())
                .connRole(WorkerEndpointRoles.TASK_DISPATCH)
                .eventCode(request.getEventCode())
                .project(request.getProject())
                .traceId(messageId)
                .receivedAt(System.currentTimeMillis())
                .rawJson(rawJson)
                .build();
        WorkerDebugMessageStore.recordOutbound(
                request.getWorkerId(),
                request.getProject(),
                request.getEventCode(),
                MessageType.CONTROL.name(),
                WorkerControlEventProtocol.SUB_MSG_TYPE,
                messageId,
                GSON.toJson(payloadJson),
                rawJson,
                "message queued to dispatcher"
        );
        messageTransporter.sendOutput(envelope);
        return new WorkerControlEventPublishResult(
                messageId,
                request.getWorkerId(),
                request.getProject(),
                request.getEventCode(),
                request.getRequestId()
        );
    }

    private MessageContext buildMessageContext(String workerId) {
        MessageContext context = new MessageContext();
        context.setWorkerId(workerId);
        context.setConnRole(WorkerEndpointRoles.TASK_DISPATCH);
        return context;
    }

    private String encodeMessage(MassMessage message) {
        return messageCodec != null ? messageCodec.encode(message) : GSON.toJson(message);
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
}
