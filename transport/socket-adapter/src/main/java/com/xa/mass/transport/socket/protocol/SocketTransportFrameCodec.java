package com.xa.mass.transport.socket.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.xa.mass.contract.worker.WorkerChannelFrameJsonCodec;
import com.xa.mass.transport.runtime.frame.TransportJsonFrameParser;
import com.xa.mass.transport.runtime.delivery.DispatchMessage;

/**
 * Adapter-local line-delimited JSON codec for the socket adapter.
 */
public final class SocketTransportFrameCodec {

    private static final String TYPE_FIELD = "type";
    private static final String WORKER_ID_FIELD = "workerId";
    private static final String WORKER_GROUP_ID_FIELD = "workerGroupId";
    private static final String TRACE_ID_FIELD = "traceId";

    private final TransportJsonFrameParser jsonFrameParser;
    private final WorkerChannelFrameJsonCodec workerFrameCodec;

    public SocketTransportFrameCodec() {
        this(new GsonBuilder().create());
    }

    public SocketTransportFrameCodec(Gson gson) {
        this.jsonFrameParser = new TransportJsonFrameParser(gson);
        this.workerFrameCodec = new WorkerChannelFrameJsonCodec();
    }

    public JsonObject parseObject(String json) {
        return jsonFrameParser.parseObject(json);
    }

    public boolean isHelloFrame(JsonObject frame) {
        return "hello".equalsIgnoreCase(readString(frame, TYPE_FIELD))
                && readString(frame, WORKER_ID_FIELD) != null;
    }

    public boolean isHeartbeatFrame(JsonObject frame) {
        return "heartbeat".equalsIgnoreCase(readString(frame, TYPE_FIELD));
    }

    public String extractWorkerId(JsonObject frame) {
        return readString(frame, WORKER_ID_FIELD);
    }

    public String extractWorkerGroupId(JsonObject frame) {
        return readString(frame, WORKER_GROUP_ID_FIELD);
    }

    public String extractTraceId(JsonObject frame) {
        return readString(frame, TRACE_ID_FIELD);
    }

    public String encodeCanonicalTaskDispatch(DispatchMessage item) {
        if (item == null) {
            throw new IllegalArgumentException("item must not be null");
        }
        return workerFrameCodec.encodeAction(item.payload());
    }

    private String readString(JsonObject object, String field) {
        return jsonFrameParser.readString(object, field);
    }

}
