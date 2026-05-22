package com.xa.mass.transport.packet;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class JsonTransportPacketCodec implements TransportPacketCodec {

    private final Gson gson;

    public JsonTransportPacketCodec() {
        this(new GsonBuilder().create());
    }

    public JsonTransportPacketCodec(Gson gson) {
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    @Override
    public byte[] encode(TransportPacket packet) {
        Objects.requireNonNull(packet, "packet");
        return gson.toJson(packet).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public TransportPacket decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        DecodedTransportPacketRecord decoded = gson.fromJson(
                new String(bytes, StandardCharsets.UTF_8),
                DecodedTransportPacketRecord.class
        );
        if (decoded == null) {
            throw new IllegalArgumentException("decoded transport packet must not be null");
        }
        return TransportPacket.fromDecodedJson(
                decoded.version,
                decoded.packetId,
                decoded.traceId,
                decoded.type,
                decoded.adapterId,
                decoded.routeKey,
                decoded.taskId,
                decoded.messageId,
                decoded.attemptId,
                decoded.eventCode,
                decoded.contentType,
                decoded.payload
        );
    }

    @Override
    public String contentType() {
        return TransportPacket.JSON_CONTENT_TYPE;
    }

    private static final class DecodedTransportPacketRecord {
        private int version;
        private String packetId;
        private String traceId;
        private PacketType type;
        private String adapterId;
        private String routeKey;
        private String taskId;
        private String messageId;
        private String attemptId;
        private String eventCode;
        private String contentType;
        private java.util.Map<String, Object> payload;
    }
}
