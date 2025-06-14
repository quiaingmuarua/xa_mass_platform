package com.xa.mass.server;

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

    // 使用设备ID作为key，Channel作为value
    private final Map<String, Channel> deviceChannels = new ConcurrentHashMap<>();
    
    // 使用Channel作为key，设备ID作为value（用于反向查找）
    private final Map<Channel, String> channelDevices = new ConcurrentHashMap<>();

    /**
     * 添加新的设备连接
     * @param deviceId 设备ID
     * @param channel WebSocket通道
     */
    public void addSession(String deviceId, Channel channel) {
        deviceChannels.put(deviceId, channel);
        channelDevices.put(channel, deviceId);
        logger.info("Device {} connected, total connections: {}", deviceId, deviceChannels.size());
    }

    /**
     * 移除设备连接
     * @param channel WebSocket通道
     */
    public void removeSession(Channel channel) {
        String deviceId = channelDevices.remove(channel);
        if (deviceId != null) {
            deviceChannels.remove(deviceId);
            logger.info("Device {} disconnected, total connections: {}", deviceId, deviceChannels.size());
        }
    }

    /**
     * 向指定设备发送消息
     * @param deviceId 设备ID
     * @param message 消息内容
     * @return 是否发送成功
     */
    public boolean sendMessage(String deviceId, String message) {
        Channel channel = deviceChannels.get(deviceId);
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(new TextWebSocketFrame(message));
            return true;
        }
        logger.warn("Failed to send message to device {}, channel not found or inactive", deviceId);
        return false;
    }

    /**
     * 广播消息给所有连接的设备
     * @param message 消息内容
     */
    public void broadcastMessage(String message) {
        TextWebSocketFrame frame = new TextWebSocketFrame(message);
        deviceChannels.values().forEach(channel -> {
            if (channel.isActive()) {
                channel.writeAndFlush(frame.copy());
            }
        });
    }

    /**
     * 获取设备连接数
     * @return 当前连接的设备数量
     */
    public int getConnectionCount() {
        return deviceChannels.size();
    }

    /**
     * 检查设备是否在线
     * @param deviceId 设备ID
     * @return 设备是否在线
     */
    public boolean isDeviceOnline(String deviceId) {
        Channel channel = deviceChannels.get(deviceId);
        return channel != null && channel.isActive();
    }

    /**
     * 获取设备ID
     * @param channel WebSocket通道
     * @return 设备ID，如果未找到则返回null
     */
    public String getDeviceId(Channel channel) {
        return channelDevices.get(channel);
    }
} 