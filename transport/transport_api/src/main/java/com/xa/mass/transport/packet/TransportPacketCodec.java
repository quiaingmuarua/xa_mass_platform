package com.xa.mass.transport.packet;

public interface TransportPacketCodec {

    byte[] encode(TransportPacket packet);

    TransportPacket decode(byte[] bytes);

    String contentType();
}
