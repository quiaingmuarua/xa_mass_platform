package com.xa.mass.core.server.manager;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ServerSessionManager {
    private static final Logger logger = LoggerFactory.getLogger(ServerSessionManager.class);

    // deviceId -> connRole -> Channel
    private final Map<String, Map<String, Channel>> deviceChannelMap = new ConcurrentHashMap<>();

    // deviceId -> connRole -> ChannelHandlerContext
    private final Map<String, Map<String, ChannelHandlerContext>> deviceChannelCtxMap = new ConcurrentHashMap<>();

    // Channel -> [deviceId, connRole] 反向索引
    private final Map<Channel, DeviceConnKey> channelIndex = new ConcurrentHashMap<>();


    /**
     * 添加新连
     */
    public synchronized void addSession(String deviceId, String connRole, Channel channel, ChannelHandlerContext ctx) {
        deviceChannelMap
                .computeIfAbsent(deviceId, k -> new ConcurrentHashMap<>())
                .put(connRole, channel);

        deviceChannelCtxMap
                .computeIfAbsent(deviceId, k -> new ConcurrentHashMap<>())
                .put(connRole, ctx);

        channelIndex.put(channel, new DeviceConnKey(deviceId, connRole));

        logger.info("🟢 Connected: deviceId={} role={} totalDevices={}", deviceId, connRole, deviceChannelMap.size());
    }

    /**
     * 移除连接
     */
    public synchronized void removeSession(Channel channel) {
        DeviceConnKey key = channelIndex.remove(channel);
        if (key != null) {
            Map<String, Channel> roleMap = deviceChannelMap.get(key.getDeviceId());
            if (roleMap != null) {
                roleMap.remove(key.getConnRole());
                if (roleMap.isEmpty()) {
                    deviceChannelMap.remove(key.getDeviceId());
                }
            }
            Map<String, ChannelHandlerContext> roleCtxMap = deviceChannelCtxMap.get(key.getDeviceId());
            if (roleCtxMap != null) {
                roleCtxMap.remove(key.getConnRole());
                if (roleCtxMap.isEmpty()) {
                    deviceChannelCtxMap.remove(key.getDeviceId());
                }
            }
            logger.info("🔌 Disconnected: deviceId={} role={}", key.getDeviceId(), key.getConnRole());
        }
    }

    /**
     * 向某个设备的某个连接角色发送消
     */
    public boolean sendMessage(String deviceId, String connRole, String message) {
        Map<String, Channel> roleMap = deviceChannelMap.get(deviceId);
        if (roleMap != null) {
            Channel channel = roleMap.get(connRole);
            if (channel != null && channel.isActive()) {
                // 也可以从 deviceChannelCtxMap 获取 ctx 来发送，Channel 本身就可以发
                channel.writeAndFlush(new TextWebSocketFrame(message));
                return true;
            }
        }
        logger.warn("Failed to send to device={}, role={}", deviceId, connRole);
        return false;
    }

    /**
     * 广播给所有连
     */
    public void broadcastMessage(String message) {
        TextWebSocketFrame frame = new TextWebSocketFrame(message);
        deviceChannelMap.values().forEach(roleMap ->
                roleMap.values().forEach(channel -> {
                    if (channel.isActive()) {
                        channel.writeAndFlush(frame.copy());
                    }
                })
        );
    }

    public boolean isDeviceOnline(String deviceId, String connRole) {
        Map<String, Channel> roleMap = deviceChannelMap.get(deviceId);
        if (roleMap == null) return false;
        Channel ch = roleMap.get(connRole);
        return ch != null && ch.isActive();
    }

    public int getDeviceConnectionCount() {
        return channelIndex.size();
    }

    public DeviceConnKey getDeviceConnKey(Channel channel) {
        return channelIndex.get(channel);
    }

    public Channel getChannel(String deviceId, String connRole) {
        Map<String, Channel> roleMap = deviceChannelMap.get(deviceId);
        return roleMap != null ? roleMap.get(connRole) : null;
    }

    public ChannelHandlerContext getChannelContext(String deviceId, String connRole) {
        Map<String, ChannelHandlerContext> roleCtxMap = deviceChannelCtxMap.get(deviceId);
        return roleCtxMap != null ? roleCtxMap.get(connRole) : null;
    }
}
