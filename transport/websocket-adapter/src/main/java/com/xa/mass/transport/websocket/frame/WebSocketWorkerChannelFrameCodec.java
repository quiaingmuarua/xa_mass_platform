package com.xa.mass.transport.websocket.frame;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.util.Objects;
import java.util.UUID;

public final class WebSocketWorkerChannelFrameCodec {
    public static final String ACTION = "ACTION";
    public static final String ACTION_REPLY = "ACTION_REPLY";
    public static final String EVIDENCE_REPORT = "EVIDENCE_REPORT";
    public static final String HEARTBEAT = "HEARTBEAT";

    private static final String FRAME_ID_FIELD = "frameId";
    private static final String KIND_FIELD = "kind";
    private static final String BODY_FIELD = "body";

    private final Gson gson;
    private final WebSocketJsonFrameParser parser;

    public WebSocketWorkerChannelFrameCodec() {
        this(new GsonBuilder().create(), new WebSocketJsonFrameParser());
    }

    WebSocketWorkerChannelFrameCodec(Gson gson, WebSocketJsonFrameParser parser) {
        this.gson = Objects.requireNonNull(gson, "gson");
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    public String actionFrame(String body) {
        return frame(ACTION, body);
    }

    public String frame(String kind, String body) {
        JsonObject frame = new JsonObject();
        frame.addProperty(FRAME_ID_FIELD, UUID.randomUUID().toString());
        frame.addProperty(KIND_FIELD, requireText(kind, "kind"));
        frame.addProperty(BODY_FIELD, body == null ? "" : body);
        return gson.toJson(frame);
    }

    public boolean isKind(JsonObject frame, String kind) {
        String actual = parser.readString(frame, KIND_FIELD);
        return actual != null && actual.equals(kind);
    }

    public String body(JsonObject frame) {
        return parser.readString(frame, BODY_FIELD);
    }

    public String frameId(JsonObject frame) {
        return parser.readString(frame, FRAME_ID_FIELD);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }
}
