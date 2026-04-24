package com.xa.mass.gateway.dispatcher;

import com.google.gson.JsonObject;
import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.gateway.queue.MessageCodec;
import com.xa.mass.transport.channel.NoopWorkerSystemEventChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Classifier for current WebSocket compatibility frames.
 *
 * <p>This type intentionally stays protocol-local. It may classify current wire
 * tuples into a small fixed set of compatibility frame kinds, but it must not
 * become a second platform routing model alongside global {@code eventCode}.
 */
public class GatewayCompatibilityFrameClassifier {
    private static final Logger log = LoggerFactory.getLogger(GatewayCompatibilityFrameClassifier.class);
    private static final String SUBTYPE_HEARTBEAT = "heartbeat";

    private final WorkerSystemEventChannel systemEventChannel;
    private final MessageCodec messageCodec;

    public GatewayCompatibilityFrameClassifier(MessageCodec messageCodec) {
        this(messageCodec, NoopWorkerSystemEventChannel.INSTANCE);
    }

    public GatewayCompatibilityFrameClassifier(MessageCodec messageCodec, WorkerSystemEventChannel systemEventChannel) {
        this.messageCodec = messageCodec;
        this.systemEventChannel = systemEventChannel != null ? systemEventChannel : NoopWorkerSystemEventChannel.INSTANCE;
    }

    public GatewayCompatibilityFrameKind classify(JsonObject frame) {
        if (frame == null) {
            return GatewayCompatibilityFrameKind.UNKNOWN;
        }
        if (isHeartbeatPing(frame)) {
            return GatewayCompatibilityFrameKind.PING_HEARTBEAT;
        }
        if (isHeartbeatPong(frame)) {
            return GatewayCompatibilityFrameKind.PONG_HEARTBEAT;
        }
        if (isTaskStep(frame)) {
            return GatewayCompatibilityFrameKind.TASK_STEP;
        }
        if (isControlEventResponse(frame)) {
            return GatewayCompatibilityFrameKind.CONTROL_EVENT_RESPONSE;
        }
        if (isControlEventRequest(frame)) {
            return GatewayCompatibilityFrameKind.CONTROL_EVENT_REQUEST;
        }
        return GatewayCompatibilityFrameKind.UNKNOWN;
    }

    public String encodeHeartbeatPong(JsonObject frame) {
        String workerId = messageCodec.extractWorkerId(frame);
        String msgId = messageCodec.extractMessageId(frame);
        log.debug("Received ping from {}/{}", workerId, messageCodec.extractConnRole(frame));
        systemEventChannel.publishWorkerHeartbeat(workerId, "heartbeat", msgId);
        return messageCodec.encodeHeartbeatPong(frame);
    }

    public void recordHeartbeatPong(JsonObject frame) {
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
