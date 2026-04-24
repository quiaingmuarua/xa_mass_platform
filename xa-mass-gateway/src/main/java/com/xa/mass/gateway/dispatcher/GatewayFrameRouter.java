package com.xa.mass.gateway.dispatcher;

import com.google.gson.JsonObject;
import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.gateway.queue.MessageCodec;
import com.xa.mass.transport.channel.NoopWorkerSystemEventChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Router for current WebSocket compatibility frames.
 */
public class GatewayFrameRouter {
    private static final Logger log = LoggerFactory.getLogger(GatewayFrameRouter.class);
    private static final String SUBTYPE_HEARTBEAT = "heartbeat";

    private final WorkerSystemEventChannel systemEventChannel;
    private final MessageCodec messageCodec;

    public GatewayFrameRouter(MessageCodec messageCodec) {
        this(messageCodec, NoopWorkerSystemEventChannel.INSTANCE);
    }

    public GatewayFrameRouter(MessageCodec messageCodec, WorkerSystemEventChannel systemEventChannel) {
        this.messageCodec = messageCodec;
        this.systemEventChannel = systemEventChannel != null ? systemEventChannel : NoopWorkerSystemEventChannel.INSTANCE;
    }

    public GatewayFrameKind route(JsonObject frame) {
        if (frame == null) {
            return GatewayFrameKind.UNKNOWN;
        }
        if (isHeartbeatPing(frame)) {
            return GatewayFrameKind.PING_HEARTBEAT;
        }
        if (isHeartbeatPong(frame)) {
            return GatewayFrameKind.PONG_HEARTBEAT;
        }
        if (isTaskStep(frame)) {
            return GatewayFrameKind.TASK_STEP;
        }
        if (isControlEventResponse(frame)) {
            return GatewayFrameKind.CONTROL_EVENT_RESPONSE;
        }
        if (isControlEventRequest(frame)) {
            return GatewayFrameKind.CONTROL_EVENT_REQUEST;
        }
        return GatewayFrameKind.UNKNOWN;
    }

    public String handlePing(JsonObject frame) {
        String workerId = messageCodec.extractWorkerId(frame);
        String msgId = messageCodec.extractMessageId(frame);
        log.debug("Received ping from {}/{}", workerId, messageCodec.extractConnRole(frame));
        systemEventChannel.publishWorkerHeartbeat(workerId, "heartbeat", msgId);
        return messageCodec.encodeHeartbeatPong(frame);
    }

    public void handlePong(JsonObject frame) {
        log.debug("Received pong from {}/{}", messageCodec.extractWorkerId(frame), messageCodec.extractConnRole(frame));
    }

    private boolean isControlEventRequest(JsonObject frame) {
        return !isResponse(frame)
                && "CONTROL".equals(readString(frame, "msgType"))
                && WorkerControlEventProtocol.SUB_MSG_TYPE.equals(normalizeSubType(readString(frame, "subMsgType")))
                && messageCodec.extractEventCode(frame) != null;
    }

    private boolean isControlEventResponse(JsonObject frame) {
        return isResponse(frame)
                && "CONTROL".equals(readString(frame, "msgType"))
                && WorkerControlEventProtocol.SUB_MSG_TYPE.equals(normalizeSubType(readString(frame, "subMsgType")));
    }

    private boolean isHeartbeatPing(JsonObject frame) {
        return "PING".equals(readString(frame, "msgType"))
                && SUBTYPE_HEARTBEAT.equals(normalizeSubType(readString(frame, "subMsgType")));
    }

    private boolean isHeartbeatPong(JsonObject frame) {
        return "PONG".equals(readString(frame, "msgType"))
                && SUBTYPE_HEARTBEAT.equals(normalizeSubType(readString(frame, "subMsgType")));
    }

    private boolean isTaskStep(JsonObject frame) {
        return "TASK".equals(readString(frame, "msgType"))
                && "step".equals(normalizeSubType(readString(frame, "subMsgType")));
    }

    private boolean isResponse(JsonObject frame) {
        return frame != null && frame.has("response") && !frame.get("response").isJsonNull() && frame.get("response").getAsBoolean();
    }

    private String normalizeSubType(String subMsgType) {
        if (subMsgType == null || subMsgType.isBlank()) {
            return null;
        }
        return subMsgType.trim();
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
}
