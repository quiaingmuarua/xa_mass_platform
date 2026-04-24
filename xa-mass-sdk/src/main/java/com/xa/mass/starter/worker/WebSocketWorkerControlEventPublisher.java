package com.xa.mass.starter.worker;

import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.base.debug.WorkerDebugMessageStore;
import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.xa.mass.gateway.queue.MessageCodec;
import com.xa.mass.gateway.queue.OutboundDelivery;
import com.xa.mass.starter.transport.WorkerControlEventDispatch;
import com.xa.mass.starter.transport.WorkerControlEventPublishResult;
import com.xa.mass.starter.transport.WorkerControlEventPublisher;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.WorkerEndpointRoles;

import java.util.UUID;

/**
 * WebSocket adapter-local publisher for outbound worker control/debug events.
 */
public final class WebSocketWorkerControlEventPublisher implements WorkerControlEventPublisher {

    private static final Gson GSON = new Gson();

    private final MessageTransporter<String, OutboundDelivery> messageTransporter;
    private final WorkerEndpointRegistry endpointRegistry;
    private final MessageCodec messageCodec;

    public WebSocketWorkerControlEventPublisher(MessageTransporter<String, OutboundDelivery> messageTransporter,
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

        String messageId = UUID.randomUUID().toString();
        String rawJson = encodeWorkerControlEventDispatch(request, messageId);
        WorkerDebugMessageStore.recordOutbound(
                request.getWorkerId(),
                request.getProject(),
                request.getEventCode(),
                "CONTROL",
                WorkerControlEventProtocol.SUB_MSG_TYPE,
                messageId,
                request.getPayload() == null ? "{}" : request.getPayload().toString(),
                rawJson,
                "message queued to dispatcher"
        );
        messageTransporter.sendOutput(new OutboundDelivery(
                request.getWorkerId(),
                WorkerEndpointRoles.TASK_DISPATCH,
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
        frame.addProperty("msgId", messageId);
        frame.addProperty("response", false);
        frame.addProperty("msgType", "CONTROL");
        frame.addProperty("subMsgType", WorkerControlEventProtocol.SUB_MSG_TYPE);
        frame.addProperty("from", "SERVER");
        if (request.getProject() != null) {
            frame.addProperty("project", request.getProject());
        }
        JsonObject context = new JsonObject();
        context.addProperty("workerId", request.getWorkerId());
        context.addProperty("connRole", WorkerEndpointRoles.TASK_DISPATCH);
        frame.add("context", context);
        JsonElement payload = GSON.toJsonTree(request.getPayload());
        frame.add("payload", payload != null ? payload : new JsonObject());
        return GSON.toJson(frame);
    }
}
