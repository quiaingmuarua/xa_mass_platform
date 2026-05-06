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
        return gson.fromJson(new String(bytes, StandardCharsets.UTF_8), TransportPacket.class);
    }

    @Override
    public String contentType() {
        return TransportPacket.JSON_CONTENT_TYPE;
    }
}
