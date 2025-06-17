package com.xa.mass.core.session;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
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
     * 添加新连接，如果已存在且可用则跳过
     */
    public synchronized void addSession(String deviceId, String connRole, Channel channel, ChannelHandlerContext ctx) {
        // 检查连接是否已存在且可用
        Map<String, Channel> existingRoleMap = deviceChannelMap.get(deviceId);
        if (existingRoleMap != null) {
            Channel existingChannel = existingRoleMap.get(connRole);
            // 如果通道相同且仍然活动，则认为是同一个连接，直接返回
            if (existingChannel != null && existingChannel == channel && existingChannel.isActive()) {
                logger.debug("Session for deviceId={} role={} already exists and is active. Skipping add.", deviceId, connRole);
                return;
            }
            // 如果通道不同，或者旧通道不活动，则视为新连接或重连，继续执行添加逻辑
            // 此时可能需要先移除旧的（如果存在且与当前 channel 不同）
            if (existingChannel != null && existingChannel != channel) {
                logger.warn("Existing channel for deviceId={} role={} found, but new channel is different. Replacing session.", deviceId, connRole);
                // 显式移除旧的，避免 channelIndex 中残留旧 channel 的映射
                removeSession(existingChannel);
            }
        }

        deviceChannelMap
                .computeIfAbsent(deviceId, k -> new ConcurrentHashMap<>())
                .put(connRole, channel);

        deviceChannelCtxMap
                .computeIfAbsent(deviceId, k -> new ConcurrentHashMap<>())
                .put(connRole, ctx);

        channelIndex.put(channel, new DeviceConnKey(deviceId, connRole));

        logger.info("🟢 Connected: deviceId={} role={} channelId={} totalDevices={}",
                deviceId, connRole, channel.id().asShortText(), deviceChannelMap.size());
    }

    /**
     * 移除连接
     */
    public synchronized void removeSession(Channel channel) {
        DeviceConnKey key = channelIndex.remove(channel);
        if (key != null) {
            Map<String, Channel> roleMap = deviceChannelMap.get(key.getDeviceId());
            if (roleMap != null) {
                // 仅当存储的 channel 与当前要移除的 channel 是同一个实例时才移除
                if (channel.equals(roleMap.get(key.getConnRole()))) {
                    roleMap.remove(key.getConnRole());
                }
                if (roleMap.isEmpty()) {
                    deviceChannelMap.remove(key.getDeviceId());
                }
            }
            Map<String, ChannelHandlerContext> roleCtxMap = deviceChannelCtxMap.get(key.getDeviceId());
            if (roleCtxMap != null) {
                // 同样，仅当存储的 channel 与当前要移除的 channel 是同一个实例时才移除对应的 context
                // (通过 channelId 间接判断，因为 ctx 可能不同但 channel 相同)
                // Channel existingChannelInMap = deviceChannelMap.getOrDefault(key.getDeviceId(), Map.of()).get(key.getConnRole()); // 旧代码
                Channel existingChannelInMap = deviceChannelMap.getOrDefault(key.getDeviceId(), Collections.emptyMap()).get(key.getConnRole()); // 修改后
                if (channel.equals(existingChannelInMap)) {
                    roleCtxMap.remove(key.getConnRole());
                }
                if (roleCtxMap.isEmpty()) {
                    deviceChannelCtxMap.remove(key.getDeviceId());
                }
            }
            logger.info("🔌 Disconnected: deviceId={} role={} channelId={}",
                    key.getDeviceId(), key.getConnRole(), channel.id().asShortText());
        } else {
            logger.warn("Attempted to remove session for a channel not in index: {}", channel.id().asShortText());
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
        logger.warn("Failed to send to device={}, role={}. Channel not found or inactive.", deviceId, connRole);
        return false;
    }

    /**
     * 广播给所有连接
     */
    public void broadcastMessage(String message) {
        TextWebSocketFrame frame = new TextWebSocketFrame(message);
        int sentCount = 0;
        for (Map<String, Channel> roleMap : deviceChannelMap.values()) {
            for (Channel channel : roleMap.values()) {
                if (channel.isActive()) {
                    channel.writeAndFlush(frame.copy());
                    sentCount++;
                }
            }
        }
        logger.debug("Broadcast message sent to {} active channels.", sentCount);
    }

    public boolean isDeviceOnline(String deviceId, String connRole) {
        Map<String, Channel> roleMap = deviceChannelMap.get(deviceId);
        if (roleMap == null) return false;
        Channel ch = roleMap.get(connRole);
        return ch != null && ch.isActive();
    }

    public int getDeviceConnectionCount() {
        // 更准确地计算活动连接数
        return (int) channelIndex.keySet().stream().filter(Channel::isActive).count();
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