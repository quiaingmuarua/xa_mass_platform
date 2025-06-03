package com.xa.mass.server;

import io.netty.channel.Channel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ClientSessionManager {
    private static final Map<String, Channel> clientChannels = new ConcurrentHashMap<>();

    public static void register(String clientId, Channel channel) {
        clientChannels.put(clientId, channel);
    }

    public static void remove(String clientId) {
        clientChannels.remove(clientId);
    }

    public static Channel getChannel(String clientId) {
        return clientChannels.get(clientId);
    }
}