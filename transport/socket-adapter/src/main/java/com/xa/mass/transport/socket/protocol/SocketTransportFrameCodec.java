package com.xa.mass.transport.socket.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.xa.mass.transport.runtime.frame.TransportJsonFrameParser;
import com.xa.mass.transport.packet.TransportPacket;
import com.xa.mass.transport.runtime.delivery.DispatchMessage;

/**
 * Adapter-local line-delimited JSON codec for the socket adapter.
 */
public final class SocketTransportFrameCodec {

    private static final String TYPE_FIELD = "type";
    private static final String WORKER_GROUP_ID_FIELD = "workerGroupId";
    private static final String ROUTE_KEY_FIELD = "routeKey";
    private static final String TRACE_ID_FIELD = "traceId";
    private static final String RESULT_CORRELATION_REF_FIELD = "resultCorrelationRef";
    private static final String EVENT_CODE_FIELD = "eventCode";

    private final TransportJsonFrameParser jsonFrameParser;

    public SocketTransportFrameCodec() {
        this(new GsonBuilder().create());
    }

    public SocketTransportFrameCodec(Gson gson) {
        this.jsonFrameParser = new TransportJsonFrameParser(gson);
    }

    public JsonObject parseObject(String json) {
        return jsonFrameParser.parseObject(json);
    }

    public boolean isHelloFrame(JsonObject frame) {
        return "hello".equalsIgnoreCase(readString(frame, TYPE_FIELD))
                && readString(frame, TransportPacket.PAYLOAD_WORKER_ID) != null;
    }

    public boolean isHeartbeatFrame(JsonObject frame) {
        return "heartbeat".equalsIgnoreCase(readString(frame, TYPE_FIELD));
    }

    public String extractWorkerId(JsonObject frame) {
        return readString(frame, TransportPacket.PAYLOAD_WORKER_ID);
    }

    public String extractWorkerGroupId(JsonObject frame) {
        return readString(frame, WORKER_GROUP_ID_FIELD);
    }

    public String extractRouteKey(JsonObject frame) {
        return readString(frame, ROUTE_KEY_FIELD);
    }

    public String extractTraceId(JsonObject frame) {
        return readString(frame, TRACE_ID_FIELD);
    }

    public String extractResultCorrelationRef(JsonObject frame) {
        return readString(frame, RESULT_CORRELATION_REF_FIELD);
    }

    public String extractEventCode(JsonObject frame) {
        return readString(frame, EVENT_CODE_FIELD);
    }

    public boolean isCanonicalTaskResult(JsonObject frame) {
        return frame != null
                && extractEventCode(frame) == null
                && readString(frame, RESULT_CORRELATION_REF_FIELD) != null
                && extractResultCorrelationRef(frame) != null
                && !isHelloFrame(frame)
                && !isHeartbeatFrame(frame);
    }

    public String encodeCanonicalTaskDispatch(DispatchMessage item) {
        if (item == null) {
            throw new IllegalArgumentException("item must not be null");
        }
        return item.payload();
    }

    public String encodeCanonicalTaskResultPayload(JsonObject frame) {
        String resultCorrelationRef = readString(frame, RESULT_CORRELATION_REF_FIELD);
        if (resultCorrelationRef == null) {
            throw new IllegalArgumentException(RESULT_CORRELATION_REF_FIELD + " is required");
        }
        return jsonFrameParser.toJson(frame);
    }

    private String readString(JsonObject object, String field) {
        return jsonFrameParser.readString(object, field);
    }

}
