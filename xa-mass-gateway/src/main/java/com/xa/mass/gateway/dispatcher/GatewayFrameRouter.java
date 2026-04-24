package com.xa.mass.gateway.dispatcher;

import com.google.gson.Gson;
import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.gateway.dispatcher.handler.MassMessageHandler;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageAckPayload;
import com.xa.mass.transport.channel.NoopWorkerSystemEventChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * Router for current WebSocket compatibility frames.
 *
 * <p>This type is adapter-facing only. New business/control capabilities must
 * not be registered here by tuple; they should flow through SDK event
 * definitions and use explicit compatibility bridges where needed.
 */
public class GatewayFrameRouter {
    private static final Logger log = LoggerFactory.getLogger(GatewayFrameRouter.class);
    private static final String SUBTYPE_HEARTBEAT = "heartbeat";

    private final Gson gson = new Gson();
    private final WorkerSystemEventChannel systemEventChannel;
    private MassMessageHandler taskDispatchHandler;
    private MassMessageHandler workerControlEventRequestBridge;
    private MassMessageHandler workerControlEventResponseHandler;

    public GatewayFrameRouter() {
        this(NoopWorkerSystemEventChannel.INSTANCE);
    }

    public GatewayFrameRouter(WorkerSystemEventChannel systemEventChannel) {
        this.systemEventChannel = systemEventChannel != null
                ? systemEventChannel
                : NoopWorkerSystemEventChannel.INSTANCE;
    }

    public FrameRouteResolution route(MassMessage frame) {
        if (frame == null || frame.getMsgType() == null) {
            return FrameRouteResolution.notFound();
        }
        if (isHeartbeatPing(frame)) {
            return FrameRouteResolution.matched(this::handlePing, "builtin-ping");
        }
        if (isHeartbeatPong(frame)) {
            return FrameRouteResolution.matched(this::handlePong, "builtin-pong");
        }
        if (isTaskDispatchFrame(frame) && taskDispatchHandler != null) {
            return FrameRouteResolution.matched(taskDispatchHandler, "task-dispatch");
        }
        if (shouldRouteToWorkerControlEventResponse(frame)) {
            return FrameRouteResolution.matched(workerControlEventResponseHandler, "worker-control-event-response");
        }
        if (shouldRouteToWorkerControlEventBridge(frame)) {
            return FrameRouteResolution.matched(workerControlEventRequestBridge, "worker-control-event-bridge");
        }
        return FrameRouteResolution.notFound();
    }

    public void registerTaskDispatchHandler(MassMessageHandler handler) {
        this.taskDispatchHandler = handler;
    }

    public void registerWorkerControlEventBridge(MassMessageHandler handler) {
        this.workerControlEventRequestBridge = handler;
    }

    public void registerWorkerControlEventResponseHandler(MassMessageHandler handler) {
        this.workerControlEventResponseHandler = handler;
    }

    private List<MassMessage> handlePing(MassMessage msg) {
        log.debug("Received ping from {}/{}", msg.getContext().getWorkerId(), msg.getContext().getConnRole());
        systemEventChannel.publishWorkerHeartbeat(
                msg.getContext().getWorkerId(),
                "heartbeat",
                msg.getMsgId()
        );
        MassMessage pong = new MassMessage();
        pong.setMsgId(msg.getMsgId());
        pong.setResponse(true);
        pong.setMsgType(MessageType.PONG);
        pong.setSubMsgType("");
        pong.setFrom(MessageDirection.SERVER);
        pong.setContext(msg.getContext());
        pong.setPayload(gson.toJsonTree(new MessageAckPayload(200, "pong")));
        return Collections.singletonList(pong);
    }

    private List<MassMessage> handlePong(MassMessage msg) {
        log.debug("Received pong from {}/{}", msg.getContext().getWorkerId(), msg.getContext().getConnRole());
        return Collections.emptyList();
    }

    private boolean shouldRouteToWorkerControlEventBridge(MassMessage msg) {
        if (workerControlEventRequestBridge == null
                || msg == null
                || msg.isResponse()
                || msg.getMsgType() != MessageType.CONTROL) {
            return false;
        }
        if (!WorkerControlEventProtocol.SUB_MSG_TYPE.equals(normalizeSubType(msg.getSubMsgType()))) {
            return false;
        }
        return msg.getPayload() != null
                && msg.getPayload().isJsonObject()
                && msg.getPayload().getAsJsonObject().has(WorkerControlEventProtocol.EVENT_FIELD)
                && !msg.getPayload().getAsJsonObject().get(WorkerControlEventProtocol.EVENT_FIELD).isJsonNull()
                && !msg.getPayload().getAsJsonObject().get(WorkerControlEventProtocol.EVENT_FIELD).getAsString().isBlank();
    }

    private boolean shouldRouteToWorkerControlEventResponse(MassMessage msg) {
        return workerControlEventResponseHandler != null
                && msg != null
                && msg.isResponse()
                && msg.getMsgType() == MessageType.CONTROL
                && WorkerControlEventProtocol.SUB_MSG_TYPE.equals(normalizeSubType(msg.getSubMsgType()));
    }

    private boolean isHeartbeatPing(MassMessage msg) {
        return msg.getMsgType() == MessageType.PING
                && SUBTYPE_HEARTBEAT.equals(normalizeSubType(msg.getSubMsgType()));
    }

    private boolean isHeartbeatPong(MassMessage msg) {
        return msg.getMsgType() == MessageType.PONG
                && SUBTYPE_HEARTBEAT.equals(normalizeSubType(msg.getSubMsgType()));
    }

    private boolean isTaskDispatchFrame(MassMessage msg) {
        return msg.getMsgType() == MessageType.TASK
                && "step".equals(normalizeSubType(msg.getSubMsgType()));
    }

    private String normalizeSubType(String subMsgType) {
        if (subMsgType == null || subMsgType.isBlank()) {
            return null;
        }
        return subMsgType.trim();
    }
}
