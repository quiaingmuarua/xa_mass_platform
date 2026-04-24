package com.xa.mass.starter.worker;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.base.debug.WorkerDebugMessageStore;
import com.xa.mass.gateway.queue.OutboundDelivery;
import com.xa.mass.starter.transport.WorkerControlEventDispatch;
import com.xa.mass.starter.transport.WorkerControlEventPublishResult;
import com.xa.mass.starter.transport.WorkerControlEventPublisher;
import com.xa.mass.transport.WorkerEndpointRegistry;

import java.util.UUID;

/**
 * WebSocket adapter-local publisher for outbound worker control/debug events.
 */
public final class WebSocketWorkerControlEventPublisher implements WorkerControlEventPublisher {

    private static final Gson GSON = new Gson();

    private final MessageTransporter<String, OutboundDelivery> messageTransporter;
    private final WorkerEndpointRegistry endpointRegistry;

    public WebSocketWorkerControlEventPublisher(MessageTransporter<String, OutboundDelivery> messageTransporter,
                                                WorkerEndpointRegistry endpointRegistry) {
        this.messageTransporter = messageTransporter;
        this.endpointRegistry = endpointRegistry;
    }

    @Override
    public WorkerControlEventPublishResult publish(WorkerControlEventDispatch request) {
        if (messageTransporter == null) {
            throw new IllegalStateException("Message transporter is not initialized");
        }
        if (endpointRegistry == null) {
            throw new IllegalStateException("Session manager is not initialized");
        }
        if (!endpointRegistry.isWorkerOnline(request.getWorkerId())) {
            throw new IllegalStateException("Target worker is offline");
        }

        String messageId = UUID.randomUUID().toString();
        String rawJson = encodeWorkerControlEventDispatch(request, messageId);
        WorkerDebugMessageStore.recordOutbound(
                request.getWorkerId(),
                request.getProject(),
                request.getEventCode(),
                messageId,
                GSON.toJson(request.getPayload()),
                rawJson,
                "message queued to dispatcher"
        );
        messageTransporter.sendOutput(new OutboundDelivery(
                request.getWorkerId(),
                rawJson,
                messageId
        ));
        return new WorkerControlEventPublishResult(
                messageId,
                request.getWorkerId(),
                request.getProject(),
                request.getEventCode(),
                request.getRequestId()
        );
    }

    private String encodeWorkerControlEventDispatch(WorkerControlEventDispatch request, String messageId) {
        JsonObject frame = new JsonObject();
        frame.addProperty(WorkerControlEventProtocol.MESSAGE_ID_FIELD, messageId);
        frame.addProperty(WorkerControlEventProtocol.RESPONSE_FIELD, false);
        frame.addProperty(WorkerControlEventProtocol.WORKER_ID_FIELD, request.getWorkerId());
        frame.addProperty(WorkerControlEventProtocol.PROJECT_FIELD, request.getProject());
        frame.addProperty(WorkerControlEventProtocol.EVENT_CODE_FIELD, request.getEventCode());
        frame.addProperty(WorkerControlEventProtocol.REQUEST_ID_FIELD, request.getRequestId());
        frame.add(WorkerControlEventProtocol.HEADERS_FIELD, GSON.toJsonTree(request.getHeaders()));
        JsonElement payload = GSON.toJsonTree(request.getPayload());
        frame.add(WorkerControlEventProtocol.PAYLOAD_FIELD, payload != null ? payload : new JsonObject());

        JsonObject principal = new JsonObject();
        if (request.getClientId() != null) {
            principal.addProperty(WorkerControlEventProtocol.CLIENT_ID_FIELD, request.getClientId());
        }
        if (request.getUserId() != null) {
            principal.addProperty(WorkerControlEventProtocol.USER_ID_FIELD, request.getUserId());
        }
        frame.add(WorkerControlEventProtocol.PRINCIPAL_FIELD, principal);
        return GSON.toJson(frame);
    }
}
