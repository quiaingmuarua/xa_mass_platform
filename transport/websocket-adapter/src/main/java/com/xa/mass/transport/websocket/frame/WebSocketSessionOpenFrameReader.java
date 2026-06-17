package com.xa.mass.transport.websocket.frame;

import com.google.gson.JsonObject;
import com.xa.mass.transport.websocket.util.WebSocketStringValues;
import io.netty.handler.codec.http.QueryStringDecoder;

/**
 * Reads only session-open identity fields.
 */
public final class WebSocketSessionOpenFrameReader {

    private static final String WORKER_ID_FIELD = "workerId";
    private static final String WORKER_GROUP_ID_FIELD = "workerGroupId";
    private static final String ROUTE_KEY_FIELD = "routeKey";

    private final WebSocketJsonFrameParser parser;

    public WebSocketSessionOpenFrameReader(WebSocketJsonFrameParser parser) {
        this.parser = parser;
    }

    public WebSocketSessionIdentity readHandshake(String requestUri) {
        if (requestUri == null || requestUri.isBlank()) {
            return new WebSocketSessionIdentity(null, null, null);
        }
        QueryStringDecoder decoder = new QueryStringDecoder(requestUri);
        String workerId = firstQueryValue(decoder, WORKER_ID_FIELD);
        String workerGroupId = firstQueryValue(decoder, WORKER_GROUP_ID_FIELD);
        String endpointAddress = WebSocketStringValues.firstNonBlank(
                firstQueryValue(decoder, ROUTE_KEY_FIELD),
                defaultEndpointAddress(workerGroupId)
        );
        return new WebSocketSessionIdentity(workerGroupId, endpointAddress, workerId);
    }

    public WebSocketSessionIdentity readFrame(JsonObject frame) {
        if (frame == null) {
            return new WebSocketSessionIdentity(null, null, null);
        }
        String workerId = parser.readString(frame, WORKER_ID_FIELD);
        String workerGroupId = parser.readString(frame, WORKER_GROUP_ID_FIELD);
        String endpointAddress = WebSocketStringValues.firstNonBlank(
                parser.readString(frame, ROUTE_KEY_FIELD),
                defaultEndpointAddress(workerGroupId)
        );
        return new WebSocketSessionIdentity(workerGroupId, endpointAddress, workerId);
    }

    private String firstQueryValue(QueryStringDecoder decoder, String key) {
        if (decoder == null || key == null) {
            return null;
        }
        var values = decoder.parameters().get(key);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return WebSocketStringValues.firstNonBlank(values.get(0));
    }

    private String defaultEndpointAddress(String workerGroupId) {
        String normalizedWorkerGroupId = WebSocketStringValues.firstNonBlank(workerGroupId);
        return normalizedWorkerGroupId == null ? null : "bucket:" + normalizedWorkerGroupId;
    }
}
