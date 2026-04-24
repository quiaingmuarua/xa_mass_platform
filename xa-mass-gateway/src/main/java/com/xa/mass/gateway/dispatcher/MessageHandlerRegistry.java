package com.xa.mass.gateway.dispatcher;

import com.google.gson.Gson;
import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.gateway.dispatcher.handler.MassMessageHandler;
import com.xa.mass.gateway.dispatcher.handler.ResolutionResult;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MessageHandlerRegistry {
    private static final Logger log = LoggerFactory.getLogger(MessageHandlerRegistry.class);
    private static final String SUBTYPE_HEARTBEAT = "heartbeat";

    private final Gson gson = new Gson();
    private final WorkerSystemEventChannel systemEventChannel;
    private final Map<String, MassMessageHandler> eventCompatHandlers = new ConcurrentHashMap<>();
    private MassMessageHandler taskStepHandler;
    private MassMessageHandler workerControlEventBridgeHandler;
    private boolean enableFallback = false;

    public MessageHandlerRegistry() {
        this(NoopWorkerSystemEventChannel.INSTANCE);
    }

    public MessageHandlerRegistry(WorkerSystemEventChannel systemEventChannel) {
        this.systemEventChannel = systemEventChannel != null
                ? systemEventChannel
                : NoopWorkerSystemEventChannel.INSTANCE;
    }

    public void autoRegister() {
        // Built-in compatibility lanes are resolved directly in {@link #resolve(MassMessage)}.
        // This hook remains as an initialization seam for existing bootstrap code.
    }

    /**
     * Registers a transport-protocol handler for one frame classification tuple.
     *
     * <p>This registry is a wire/protocol compatibility seam. Do not register
     * new business or control capabilities here; those belong on globally
     * unique SDK event definitions. The only supported control-plane tuple on
     * the current WebSocket adapter is the legacy compatibility bridge
     * {@code CONTROL/event}, which must be routed through
     * {@link #registerWorkerControlEventBridge(MassMessageHandler)} instead of a
     * normal tuple registration.
     */
    public void register(MessageType type, String subMsgType, MassMessageHandler handler) {
        if (type == MessageType.CONTROL
                && WorkerControlEventProtocol.SUB_MSG_TYPE.equals(subMsgType)) {
            throw new IllegalArgumentException(
                    "CONTROL/event is reserved for the worker-control compatibility bridge; "
                            + "register a global SDK event handler instead of a tuple handler"
            );
        }
        if (type == MessageType.TASK && "step".equals(normalizeSubType(subMsgType))) {
            registerTaskStepHandler(handler);
            return;
        }
        if (type == MessageType.EVENT) {
            registerEventCompatHandler(subMsgType, handler);
            return;
        }
        throw new IllegalArgumentException(
                "Only TASK/step and EVENT/* compatibility registrations are supported in the current gateway"
        );
    }

    public ResolutionResult resolve(MessageType type, String subMsgType) {
        MassMessage msg = new MassMessage();
        msg.setMsgType(type);
        msg.setSubMsgType(subMsgType);
        return resolve(msg);
    }

    public ResolutionResult resolve(MassMessage msg) {
        if (msg == null || msg.getMsgType() == null) {
            return fallbackOrNotFound(null, null, null);
        }
        if (isHeartbeatPing(msg)) {
            return ResolutionResult.found(this::handlePing, msg.getProject(), MessageType.PING.name(), SUBTYPE_HEARTBEAT, "builtin-ping");
        }
        if (isHeartbeatPong(msg)) {
            return ResolutionResult.found(this::handlePong, msg.getProject(), MessageType.PONG.name(), SUBTYPE_HEARTBEAT, "builtin-pong");
        }
        if (isGenericTask(msg)) {
            return ResolutionResult.found(this::handleTask, msg.getProject(), MessageType.TASK.name(), null, "builtin-task");
        }
        if (isTaskStep(msg) && taskStepHandler != null) {
            return ResolutionResult.found(taskStepHandler, msg.getProject(), MessageType.TASK.name(), "step", "task-step");
        }
        if (shouldRouteToWorkerControlEventBridge(msg)) {
            return ResolutionResult.found(
                    workerControlEventBridgeHandler,
                    msg.getProject(),
                    msg.getMsgType().name(),
                    msg.getSubMsgType(),
                    "worker-control-event-bridge"
            );
        }
        if (msg.getMsgType() == MessageType.EVENT) {
            MassMessageHandler handler = eventCompatHandlers.get(normalizeSubType(msg.getSubMsgType()));
            if (handler != null) {
                return ResolutionResult.found(
                        handler,
                        msg.getProject(),
                        MessageType.EVENT.name(),
                        normalizeSubType(msg.getSubMsgType()),
                        "event-compat"
                );
            }
        }
        return fallbackOrNotFound(
                msg.getProject(),
                msg.getMsgType().name(),
                normalizeSubType(msg.getSubMsgType())
        );
    }

    public void registerWorkerControlEventBridge(MassMessageHandler handler) {
        this.workerControlEventBridgeHandler = handler;
    }

    public void registerTaskStepHandler(MassMessageHandler handler) {
        this.taskStepHandler = handler;
    }

    public void registerEventCompatHandler(String subMsgType, MassMessageHandler handler) {
        String normalizedSubType = normalizeSubType(subMsgType);
        if (normalizedSubType == null) {
            throw new IllegalArgumentException("EVENT compatibility subMsgType is required");
        }
        eventCompatHandlers.put(normalizedSubType, handler);
        log.debug("Register EVENT compatibility handler: subMsgType={}, handler={}",
                normalizedSubType, handler.getClass().getName());
    }

    public boolean isEnableFallback() {
        return enableFallback;
    }

    public void setEnableFallback(boolean enableFallback) {
        this.enableFallback = enableFallback;
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

    private List<MassMessage> handleTask(MassMessage msg) {
        MassMessage ack = new MassMessage();
        ack.setMsgId(msg.getMsgId());
        ack.setResponse(true);
        ack.setMsgType(MessageType.TASK);
        ack.setSubMsgType("");
        ack.setFrom(MessageDirection.SERVER);
        ack.setContext(msg.getContext());
        ack.setPayload(gson.toJsonTree(new MessageAckPayload(200, "task received")));
        return Collections.singletonList(ack);
    }

    private boolean shouldRouteToWorkerControlEventBridge(MassMessage msg) {
        if (workerControlEventBridgeHandler == null || msg == null || msg.getMsgType() != MessageType.CONTROL) {
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

    private ResolutionResult fallbackOrNotFound(String project, String messageType, String subMsgType) {
        if (enableFallback) {
            return ResolutionResult.fallback(project, messageType, subMsgType);
        }
        return ResolutionResult.notFound(project, messageType, subMsgType);
    }

    private boolean isHeartbeatPing(MassMessage msg) {
        return msg.getMsgType() == MessageType.PING
                && SUBTYPE_HEARTBEAT.equals(normalizeSubType(msg.getSubMsgType()));
    }

    private boolean isHeartbeatPong(MassMessage msg) {
        return msg.getMsgType() == MessageType.PONG
                && SUBTYPE_HEARTBEAT.equals(normalizeSubType(msg.getSubMsgType()));
    }

    private boolean isGenericTask(MassMessage msg) {
        return msg.getMsgType() == MessageType.TASK
                && normalizeSubType(msg.getSubMsgType()) == null;
    }

    private boolean isTaskStep(MassMessage msg) {
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
