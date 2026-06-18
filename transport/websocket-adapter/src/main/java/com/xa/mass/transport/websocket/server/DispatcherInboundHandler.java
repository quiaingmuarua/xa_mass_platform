package com.xa.mass.transport.websocket.server;

import com.google.gson.JsonObject;
import com.xa.mass.transport.websocket.dispatcher.WebSocketInboundMessage;
import com.xa.mass.transport.websocket.dispatcher.WebSocketInboundMessageSink;
import com.xa.mass.transport.websocket.frame.WebSocketJsonFrameParser;
import com.xa.mass.transport.websocket.frame.WebSocketSessionIdentity;
import com.xa.mass.transport.websocket.frame.WebSocketSessionOpenFrameReader;
import com.xa.mass.transport.websocket.session.WebSocketServerSessionHandle;
import com.xa.mass.transport.websocket.util.WebSocketStringValues;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class DispatcherInboundHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private static final Logger logger = LoggerFactory.getLogger(DispatcherInboundHandler.class);
    private final WebSocketServerSessionHandle sessionHandle;
    private final WebSocketJsonFrameParser frameParser;
    private final WebSocketSessionOpenFrameReader sessionOpenFrameReader;
    private final WebSocketInboundMessageSink inboundMessageSink;

    public DispatcherInboundHandler(WebSocketJsonFrameParser frameParser,
                                    WebSocketSessionOpenFrameReader sessionOpenFrameReader,
                                    WebSocketInboundMessageSink inboundMessageSink,
                                    WebSocketServerSessionHandle sessionHandle) {
        this.sessionHandle = Objects.requireNonNull(sessionHandle, "sessionHandle");
        this.frameParser = Objects.requireNonNull(frameParser, "frameParser");
        this.sessionOpenFrameReader = Objects.requireNonNull(sessionOpenFrameReader, "sessionOpenFrameReader");
        this.inboundMessageSink = Objects.requireNonNull(inboundMessageSink, "inboundMessageSink");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msgFrame) {
        String raw = msgFrame.text();
        try {
            if (raw == null || !raw.trim().startsWith("{")) {
                sendError(ctx, "INVALID_FORMAT", "Message must be a JSON object");
                return;
            }
            JsonObject frame = frameParser.parseObject(raw);
            if (frame == null) {
                sendError(ctx, "PARSE_FAILED", "Message must be a valid JSON object");
                return;
            }

            String workerId = sessionHandle.getWorkerId(ctx.channel());
            String workerGroupId = sessionHandle.getDeliveryBucketId(ctx.channel());
            String routeKey = sessionHandle.getEndpointAddress(ctx.channel());
            if (workerId == null) {
                sendError(ctx, "SESSION_NOT_BOUND", "WebSocket session is not bound to a worker");
                return;
            }
            if (workerGroupId == null) {
                sendError(ctx, "SESSION_NOT_BOUND", "WebSocket session is not bound to a worker group");
                return;
            }
            org.slf4j.MDC.put("event", "channelRead0");
            org.slf4j.MDC.put("workerId", workerId);
            String eventCode = frameParser.readString(frame, "eventCode");
            if (eventCode != null) {
                org.slf4j.MDC.put("eventCode", eventCode);
            }
            String traceId = WebSocketStringValues.firstNonBlank(
                    frameParser.readString(frame, "traceId"),
                    frameParser.readString(frame, "resultCorrelationRef")
            );
            if (traceId != null) {
                org.slf4j.MDC.put("traceId", traceId);
            }
            String project = frameParser.readString(frame, "project");
            if (project != null) {
                org.slf4j.MDC.put("project", project);
            }
            try {
                logger.debug("channelRead0 raw frame");
            } finally {
                org.slf4j.MDC.clear();
            }
            inboundMessageSink.accept(WebSocketInboundMessage.of(
                    raw,
                    workerId,
                    routeKey,
                    ctx.channel().id().asShortText(),
                    frame
            ));
        } catch (Exception e) {
            logger.error("Unexpected error in channelRead0", e);
            sendError(ctx, "INTERNAL_ERROR", "Internal server error");
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete handshakeComplete) {
            WebSocketSessionIdentity identity = sessionOpenFrameReader.readHandshake(handshakeComplete.requestUri());
            if (!identity.complete()) {
                logger.warn("WebSocket handshake completed without workerId/workerGroupId query parameter");
            } else {
                registerSession(identity, ctx);
            }
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Channel exception: {}", cause.getMessage(), cause);
        if (ctx.channel().isActive()) {
            sendError(ctx, "CHANNEL_ERROR", cause.getMessage());
        }
    }

    private void sendError(ChannelHandlerContext ctx, String code, String message) {
        if (ctx.channel().isActive()) {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("code", code);
            values.put("message", WebSocketStringValues.firstNonBlank(message, "WebSocket channel error"));
            values.put("type", "ERROR");
            String errorJson = frameParser.toJson(values);
            ctx.writeAndFlush(new TextWebSocketFrame(errorJson));
        }
    }

    private void registerSession(WebSocketSessionIdentity identity, ChannelHandlerContext ctx) {
        String workerGroupId = identity.workerGroupId();
        String routeKey = identity.endpointAddress();
        String workerId = identity.workerId();
        if (workerGroupId == null || workerGroupId.isBlank()
                || routeKey == null || routeKey.isBlank()
                || workerId == null || workerId.isBlank()) {
            return;
        }
        String existingWorkerGroupId = sessionHandle.getDeliveryBucketId(ctx.channel());
        String existingWorkerId = sessionHandle.getWorkerId(ctx.channel());
        String existingRouteKey = sessionHandle.getEndpointAddress(ctx.channel());
        if (workerGroupId.equals(existingWorkerGroupId)
                && workerId.equals(existingWorkerId)
                && routeKey.equals(existingRouteKey)
                && sessionHandle.getChannelContext(routeKey) != null) {
            return;
        }
        sessionHandle.addSession(workerGroupId, routeKey, workerId, ctx.channel(), ctx);
    }
}
