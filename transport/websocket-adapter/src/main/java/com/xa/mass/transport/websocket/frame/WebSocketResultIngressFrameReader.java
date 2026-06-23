package com.xa.mass.transport.websocket.frame;

import com.google.gson.JsonObject;
import com.xa.mass.transport.channel.ResultIngressDiagnostics;
import com.xa.mass.transport.channel.ResultIngressEntry;
import com.xa.mass.transport.channel.ResultIngressMessage;
import com.xa.mass.transport.websocket.util.WebSocketStringValues;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds opaque result ingress envelopes from WebSocket worker result frames.
 */
public final class WebSocketResultIngressFrameReader {

    private static final String REPLY_REF_FIELD = "replyRef";
    private static final String EVENT_CODE_FIELD = "eventCode";
    private static final String TYPE_FIELD = "type";
    private static final String ROUTE_KEY_FIELD = "routeKey";
    private static final String TRACE_ID_FIELD = "traceId";

    private final String adapterId;
    private final WebSocketJsonFrameParser parser;
    private final WebSocketWorkerChannelFrameCodec channelFrameCodec;

    public WebSocketResultIngressFrameReader(String adapterId, WebSocketJsonFrameParser parser) {
        this(adapterId, parser, new WebSocketWorkerChannelFrameCodec());
    }

    WebSocketResultIngressFrameReader(String adapterId,
                                      WebSocketJsonFrameParser parser,
                                      WebSocketWorkerChannelFrameCodec channelFrameCodec) {
        this.adapterId = requireText(adapterId, "adapterId");
        this.parser = parser;
        this.channelFrameCodec = channelFrameCodec;
    }

    public boolean isResultFrame(JsonObject frame) {
        return frame != null
                && channelFrameCodec.isKind(frame, WebSocketWorkerChannelFrameCodec.ACTION_REPLY)
                && !isControlFrame(frame);
    }

    public ResultIngressEntry toEntry(JsonObject frame) {
        String payload = channelFrameCodec.body(frame);
        JsonObject reply = parser.parseObject(payload);
        if (reply == null) {
            throw new IllegalArgumentException("ACTION_REPLY body must be a JSON object");
        }
        String replyRef = WebSocketStringValues.firstNonBlank(parser.readString(reply, REPLY_REF_FIELD));
        if (replyRef == null) {
            throw new IllegalArgumentException(REPLY_REF_FIELD + " is required");
        }
        String routeKey = WebSocketStringValues.firstNonBlank(parser.readString(frame, ROUTE_KEY_FIELD));
        String traceId = WebSocketStringValues.firstNonBlank(
                parser.readString(frame, TRACE_ID_FIELD),
                channelFrameCodec.frameId(frame),
                replyRef
        );
        long now = System.currentTimeMillis();
        return new ResultIngressEntry(
                replyRef,
                new ResultIngressMessage(
                        UUID.randomUUID().toString(),
                        replyRef,
                        payload,
                        0L,
                        now
                ),
                new ResultIngressDiagnostics(diagnostics(routeKey, traceId))
        );
    }

    public String replyRef(JsonObject frame) {
        String payload = channelFrameCodec.body(frame);
        JsonObject reply = parser.parseObject(payload);
        return reply == null ? null : WebSocketStringValues.firstNonBlank(parser.readString(reply, REPLY_REF_FIELD));
    }

    public String traceId(JsonObject frame) {
        return parser.readString(frame, TRACE_ID_FIELD);
    }

    public String eventCode(JsonObject frame) {
        return parser.readString(frame, EVENT_CODE_FIELD);
    }

    public String project(JsonObject frame) {
        return parser.readString(frame, "project");
    }

    private boolean isControlFrame(JsonObject frame) {
        String type = parser.readString(frame, TYPE_FIELD);
        return type != null && switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "hello", "handshake", "heartbeat" -> true;
            default -> false;
        };
    }

    private Map<String, String> diagnostics(String routeKey, String traceId) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("adapterId", adapterId);
        if (routeKey != null && !routeKey.isBlank()) {
            values.put("routeKey", routeKey);
        }
        if (traceId != null && !traceId.isBlank()) {
            values.put("traceId", traceId);
        }
        return Map.copyOf(values);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
