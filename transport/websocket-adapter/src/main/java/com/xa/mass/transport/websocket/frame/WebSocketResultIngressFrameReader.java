package com.xa.mass.transport.websocket.frame;

import com.google.gson.JsonObject;
import com.xa.mass.transport.channel.ResultIngressEntry;
import com.xa.mass.transport.runtime.AdapterResultIngressEntries;
import com.xa.mass.transport.runtime.embedded.WorkerChannelActionReplyFrame;
import com.xa.mass.transport.runtime.embedded.WorkerChannelActionReplyReader;
import com.xa.mass.transport.runtime.frame.TransportJsonFrameParser;
import com.xa.mass.transport.websocket.util.WebSocketStringValues;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds opaque result ingress envelopes from WebSocket worker result frames.
 */
public final class WebSocketResultIngressFrameReader {

    private static final String EVENT_CODE_FIELD = "eventCode";
    private static final String TYPE_FIELD = "type";
    private static final String ROUTE_KEY_FIELD = "routeKey";
    private static final String TRACE_ID_FIELD = "traceId";

    private final String adapterId;
    private final TransportJsonFrameParser parser;
    private final WorkerChannelActionReplyReader actionReplyReader;

    public WebSocketResultIngressFrameReader(String adapterId, TransportJsonFrameParser parser) {
        this(adapterId, parser, new WorkerChannelActionReplyReader());
    }

    WebSocketResultIngressFrameReader(String adapterId,
                                      TransportJsonFrameParser parser,
                                      WorkerChannelActionReplyReader actionReplyReader) {
        this.adapterId = requireText(adapterId, "adapterId");
        this.parser = parser;
        this.actionReplyReader = actionReplyReader;
    }

    public boolean isResultFrame(JsonObject frame) {
        return frame != null
                && actionReplyReader.isActionReplyFrame(parser.toJson(frame))
                && !isControlFrame(frame);
    }

    public ResultIngressEntry toEntry(JsonObject frame) {
        WorkerChannelActionReplyFrame actionReply = actionReplyReader.read(parser.toJson(frame));
        String payload = actionReply.body();
        String replyRef = actionReply.replyRef();
        String routeKey = WebSocketStringValues.firstNonBlank(parser.readString(frame, ROUTE_KEY_FIELD));
        String traceId = WebSocketStringValues.firstNonBlank(
                parser.readString(frame, TRACE_ID_FIELD),
                actionReply.frameId(),
                replyRef
        );
        return AdapterResultIngressEntries.from(
                replyRef,
                payload,
                diagnostics(routeKey, traceId)
        );
    }

    public String replyRef(JsonObject frame) {
        try {
            return actionReplyReader.replyRef(parser.toJson(frame));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
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
