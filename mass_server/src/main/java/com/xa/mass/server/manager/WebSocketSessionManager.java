package com.xa.mass.server.manager;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebSocketSessionManager {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketSessionManager.class);

    // deviceId -> connRole -> Channel
    private final Map<String, Map<String, Channel>> deviceChannelMap = new ConcurrentHashMap<>();

    // Channel -> [deviceId, connRole] 反向索引
    private final Map<Channel, DeviceConnKey> channelIndex = new ConcurrentHashMap<>();


    /**
     * 添加新连接
     */
    public synchronized void addSession(String deviceId, String connRole, Channel channel) {
        deviceChannelMap
                .computeIfAbsent(deviceId, k -> new ConcurrentHashMap<>())
                .put(connRole, channel);

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
            logger.info("🔌 Disconnected: deviceId={} role={}", key.getDeviceId(), key.getConnRole());
        }
    }

    /**
     * 向某个设备的某个连接角色发送消息
     */
    public boolean sendMessage(String deviceId, String connRole, String message) {
        Map<String, Channel> roleMap = deviceChannelMap.get(deviceId);
        if (roleMap != null) {
            Channel channel = roleMap.get(connRole);
            if (channel != null && channel.isActive()) {
                channel.writeAndFlush(new TextWebSocketFrame(message));
                return true;
            }
        }
        logger.warn("❌ Failed to send to device={}, role={}", deviceId, connRole);
        return false;
    }

    /**
     * 广播给所有连接
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
}
