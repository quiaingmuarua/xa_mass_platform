package com.xa.mass.transport.websocket.frame;

import com.google.gson.JsonObject;
import io.netty.handler.codec.http.QueryStringDecoder;

/**
 * Reads only session-open identity fields.
 */
public final class WebSocketSessionOpenFrameReader {

    private static final String WORKER_ID_FIELD = "workerId";
    private static final String WORKER_GROUP_ID_FIELD = "workerGroupId";

    private final WebSocketJsonFrameParser parser;

    public WebSocketSessionOpenFrameReader(WebSocketJsonFrameParser parser) {
        this.parser = parser;
    }

    public WebSocketSessionIdentity readHandshake(String requestUri) {
        if (requestUri == null || requestUri.isBlank()) {
            return new WebSocketSessionIdentity(null, null);
        }
        QueryStringDecoder decoder = new QueryStringDecoder(requestUri);
        String workerId = firstQueryValue(decoder, WORKER_ID_FIELD);
        String workerGroupId = firstQueryValue(decoder, WORKER_GROUP_ID_FIELD);
        return new WebSocketSessionIdentity(workerGroupId, workerId);
    }

    public WebSocketSessionIdentity readFrame(JsonObject frame) {
        if (frame == null) {
            return new WebSocketSessionIdentity(null, null);
        }
        String workerId = parser.readString(frame, WORKER_ID_FIELD);
        String workerGroupId = parser.readString(frame, WORKER_GROUP_ID_FIELD);
        return new WebSocketSessionIdentity(workerGroupId, workerId);
    }

    private String firstQueryValue(QueryStringDecoder decoder, String key) {
        if (decoder == null || key == null) {
            return null;
        }
        var values = decoder.parameters().get(key);
        if (values == null || values.isEmpty()) {
            return null;
        }
        String value = values.get(0);
        return value == null || value.isBlank() ? null : value.trim();
    }

}
