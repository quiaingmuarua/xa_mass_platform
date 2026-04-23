package com.xa.mass.gateway.dispatcher;

import com.google.gson.Gson;
import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.gateway.dispatcher.handler.MassMessageHandler;
import com.xa.mass.gateway.dispatcher.handler.MessageRouterKeys;
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
    private static final String GLOBAL = "GLOBAL";

    private final Gson gson = new Gson();
    private final WorkerSystemEventChannel systemEventChannel;
    // project -> (key -> handler)
    private final Map<String, Map<String, MassMessageHandler>> handlerMap = new ConcurrentHashMap<>();
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
        this.register(GLOBAL, MessageType.PING, "heartbeat", this::handlePing);
        this.register(GLOBAL, MessageType.PONG, "heartbeat", this::handlePong);
        this.register(GLOBAL, MessageType.TASK, "", this::handleTask);
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
    public void register(String project, MessageType type, String subMsgType, MassMessageHandler handler) {
        if (type == MessageType.CONTROL
                && WorkerControlEventProtocol.SUB_MSG_TYPE.equals(subMsgType)) {
            throw new IllegalArgumentException(
                    "CONTROL/event is reserved for the worker-control compatibility bridge; "
                            + "register a global SDK event handler instead of a tuple handler"
            );
        }
        String key = MessageRouterKeys.of(type, subMsgType);
        String proj = (project == null || project.trim().isEmpty()) ? GLOBAL : project;
        handlerMap.computeIfAbsent(proj, ignored -> new ConcurrentHashMap<>()).put(key, handler);
        log.debug("Register handler for project={}, key={}, type={}, subMsgType={}, handler={}",
                proj, key, type, subMsgType, handler.getClass().getName());
    }

    public ResolutionResult resolve(String project, MessageType type, String subMsgType) {
        String key = MessageRouterKeys.of(type, subMsgType);
        String proj = (project == null || project.trim().isEmpty()) ? GLOBAL : project;

        MassMessageHandler handler = handlerMap.getOrDefault(proj, Collections.emptyMap()).get(key);
        if (handler != null) {
            log.debug("Resolved handler for project='{}', key='{}': {}", proj, key, handler.getClass().getName());
            return ResolutionResult.found(handler, proj, type.name(), subMsgType, "project");
        }

        if (!GLOBAL.equals(proj)) {
            handler = handlerMap.getOrDefault(GLOBAL, Collections.emptyMap()).get(key);
            if (handler != null) {
                log.debug("Resolved global handler for key='{}': {}", key, handler.getClass().getName());
                return ResolutionResult.found(handler, proj, type.name(), subMsgType, "global");
            }
        }

        if (enableFallback) {
            log.warn("No message handler found for project='{}', key='{}', using fallback", proj, key);
            return ResolutionResult.fallback(proj, type.name(), subMsgType);
        }

        log.warn("No message handler found for project='{}', key='{}'", proj, key);
        return ResolutionResult.notFound(proj, type.name(), subMsgType);
    }

    public ResolutionResult resolve(MassMessage msg) {
        ResolutionResult result = resolveWithoutFallback(msg.getProject(), msg.getMsgType(), msg.getSubMsgType());
        if (result.isFound()) {
            return result;
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
        if (enableFallback) {
            return ResolutionResult.fallback(msg.getProject(), msg.getMsgType().name(), msg.getSubMsgType());
        }
        return result;
    }

    public void registerWorkerControlEventBridge(MassMessageHandler handler) {
        this.workerControlEventBridgeHandler = handler;
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

    private ResolutionResult resolveWithoutFallback(String project, MessageType type, String subMsgType) {
        String key = MessageRouterKeys.of(type, subMsgType);
        String proj = (project == null || project.trim().isEmpty()) ? GLOBAL : project;

        MassMessageHandler handler = handlerMap.getOrDefault(proj, Collections.emptyMap()).get(key);
        if (handler != null) {
            return ResolutionResult.found(handler, proj, type.name(), subMsgType, "project");
        }
        if (!GLOBAL.equals(proj)) {
            handler = handlerMap.getOrDefault(GLOBAL, Collections.emptyMap()).get(key);
            if (handler != null) {
                return ResolutionResult.found(handler, proj, type.name(), subMsgType, "global");
            }
        }
        return ResolutionResult.notFound(proj, type.name(), subMsgType);
    }

    private boolean shouldRouteToWorkerControlEventBridge(MassMessage msg) {
        if (workerControlEventBridgeHandler == null || msg == null || msg.getMsgType() != MessageType.CONTROL) {
            return false;
        }
        if (!WorkerControlEventProtocol.SUB_MSG_TYPE.equals(msg.getSubMsgType())) {
            return false;
        }
        return msg.getPayload() != null
                && msg.getPayload().isJsonObject()
                && msg.getPayload().getAsJsonObject().has(WorkerControlEventProtocol.EVENT_FIELD)
                && !msg.getPayload().getAsJsonObject().get(WorkerControlEventProtocol.EVENT_FIELD).isJsonNull()
                && !msg.getPayload().getAsJsonObject().get(WorkerControlEventProtocol.EVENT_FIELD).getAsString().isBlank();
    }
}
