package com.xa.mass.gateway.session;

import com.xa.mass.base.channel.eventbus.core.EventPublisher;
import com.xa.mass.base.channel.eventbus.event.device.DeviceOfflineEvent;
import com.xa.mass.base.channel.eventbus.event.device.DeviceOnlineEvent;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class ServerSessionManager {

    // 榛樿鍏ㄥ眬鍞竴瀹炰緥锛堢嚎绋嬪畨鍏紝绫诲姞杞藉嵆鍒濆鍖栵級
    public static final ServerSessionManager INSTANCE = new ServerSessionManager();

    private static final Logger logger = LoggerFactory.getLogger(ServerSessionManager.class);

    // deviceId -> connRole -> Channel
    private final Map<String, Map<String, Channel>> deviceChannelMap = new ConcurrentHashMap<>();

    // deviceId -> connRole -> ChannelHandlerContext
    private final Map<String, Map<String, ChannelHandlerContext>> deviceChannelCtxMap = new ConcurrentHashMap<>();

    // Channel -> [deviceId, connRole] 鍙嶅悜绱㈠紩
    private final Map<Channel, DeviceConnKey> channelIndex = new ConcurrentHashMap<>();


    /**
     * 娣诲姞鏂拌繛鎺ワ紝濡傛灉宸插瓨鍦ㄤ笖鍙敤鍒欒烦杩?
     */
    public synchronized void addSession(String deviceId, String connRole, Channel channel, ChannelHandlerContext ctx) {
        // 妫€鏌ヨ繛鎺ユ槸鍚﹀凡瀛樺湪涓斿彲鐢?
        Map<String, Channel> existingRoleMap = deviceChannelMap.get(deviceId);
        if (existingRoleMap != null) {
            Channel existingChannel = existingRoleMap.get(connRole);
            // 濡傛灉閫氶亾鐩稿悓涓斾粛鐒舵椿鍔紝鍒欒涓烘槸鍚屼竴涓繛鎺ワ紝鐩存帴杩斿洖
            if (existingChannel != null && existingChannel == channel && existingChannel.isActive()) {
                logger.debug("Session for deviceId={} role={} already exists and is active. Skipping add.", deviceId, connRole);
                return;
            }
            // 濡傛灉閫氶亾涓嶅悓锛屾垨鑰呮棫閫氶亾涓嶆椿鍔紝鍒欒涓烘柊杩炴帴鎴栭噸杩烇紝缁х画鎵ц娣诲姞閫昏緫
            // 姝ゆ椂鍙兘闇€瑕佸厛绉婚櫎鏃х殑锛堝鏋滃瓨鍦ㄤ笖涓庡綋鍓?channel 涓嶅悓锛?
            if (existingChannel != null && existingChannel != channel) {
                logger.warn("Existing channel for deviceId={} role={} found, but new channel is different. Replacing session.", deviceId, connRole);
                // 鏄惧紡绉婚櫎鏃х殑锛岄伩鍏?channelIndex 涓畫鐣欐棫 channel 鐨勬槧灏?
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

        logger.info("馃煝 Connected: deviceId={} role={} channelId={} totalDevices={}",
                deviceId, connRole, channel.id().asShortText(), deviceChannelMap.size());
        // 鍙戝竷涓婄嚎浜嬩欢
        EventPublisher.post(new DeviceOnlineEvent(deviceId, "websocket connected", null));
    }

    /**
     * 绉婚櫎杩炴帴
     */
    public synchronized void removeSession(Channel channel) {
        DeviceConnKey key = channelIndex.remove(channel);
        if (key != null) {
            Map<String, Channel> roleMap = deviceChannelMap.get(key.getDeviceId());
            if (roleMap != null) {
                // 浠呭綋瀛樺偍鐨?channel 涓庡綋鍓嶈绉婚櫎鐨?channel 鏄悓涓€涓疄渚嬫椂鎵嶇Щ闄?
                if (channel.equals(roleMap.get(key.getConnRole()))) {
                    roleMap.remove(key.getConnRole());
                }
                if (roleMap.isEmpty()) {
                    deviceChannelMap.remove(key.getDeviceId());
                }
            }
            Map<String, ChannelHandlerContext> roleCtxMap = deviceChannelCtxMap.get(key.getDeviceId());
            if (roleCtxMap != null) {
                // Use the already-captured roleMap reference (not deviceChannelMap, which may have
                // had the entry removed above). This prevents the ctx from leaking when the channel
                // entry was just deleted in the block above.
                Channel remainingChannel = roleMap != null ? roleMap.get(key.getConnRole()) : null;
                if (remainingChannel == null) {
                    // The channel entry was removed above; remove the matching ctx too
                    roleCtxMap.remove(key.getConnRole());
                }
                if (roleCtxMap.isEmpty()) {
                    deviceChannelCtxMap.remove(key.getDeviceId());
                }
            }
            logger.info("馃攲 Disconnected: deviceId={} role={} channelId={}",
                    key.getDeviceId(), key.getConnRole(), channel.id().asShortText());
            // 鍙戝竷涓嬬嚎浜嬩欢
            EventPublisher.post(new DeviceOfflineEvent(key.getDeviceId(), "websocket disconnected", null));
        } else {
            logger.warn("Attempted to remove session for a channel not in index: {}", channel.id().asShortText());
        }
    }

    /**
     * 鍚戞煇涓澶囩殑鏌愪釜杩炴帴瑙掕壊鍙戦€佹秷鎭?
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
     * 骞挎挱缁欐墍鏈夎繛鎺?
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
        // 鏇村噯纭湴璁＄畻娲诲姩杩炴帴鏁?
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

    /**
     * 鍏抽棴鎵€鏈夋椿璺冭繛鎺ュ苟娓呴櫎鍐呴儴鐘舵€併€?
     * 鍦ㄥ簲鐢ㄥ仠鏈烘椂璋冪敤銆?
     */
    public synchronized void shutdown() {
        logger.info("Shutting down session manager, closing {} device connections...", deviceChannelMap.size());
        for (Map<String, Channel> roleMap : deviceChannelMap.values()) {
            for (Channel channel : roleMap.values()) {
                if (channel.isActive()) {
                    channel.close();
                }
            }
        }
        deviceChannelMap.clear();
        deviceChannelCtxMap.clear();
        channelIndex.clear();
        logger.info("Session manager shutdown complete.");
    }

    /**
     * 鑾峰彇褰撳墠鎵€鏈夎澶囪繛鎺?Channel 鐨勫彧璇诲壇鏈?
     *
     * @return 涓€涓寘鍚澶嘔D鍒拌鑹叉槧灏勶紝鍐嶅埌Channel鐨勫彧璇籑ap
     */
    public Map<String, Map<String, Channel>> getAllDeviceChannels() {
        Map<String, Map<String, Channel>> unmodifiableOuterMap = new HashMap<>();
        for (Map.Entry<String, Map<String, Channel>> entry : deviceChannelMap.entrySet()) {
            // 鍒涘缓鍐呴儴Map鐨勫壇鏈苟浣垮叾涓嶅彲淇敼
            Map<String, Channel> unmodifiableInnerMap = Collections.unmodifiableMap(new HashMap<>(entry.getValue()));
            unmodifiableOuterMap.put(entry.getKey(), unmodifiableInnerMap);
        }
        return Collections.unmodifiableMap(unmodifiableOuterMap);
    }

    /**
     * 鑾峰彇褰撳墠鎵€鏈夎澶囪繛鎺?ChannelHandlerContext 鐨勫彧璇诲壇鏈?
     *
     * @return 涓€涓寘鍚澶嘔D鍒拌鑹叉槧灏勶紝鍐嶅埌ChannelHandlerContext鐨勫彧璇籑ap
     */
    public Map<String, Map<String, ChannelHandlerContext>> getAllDeviceChannelContexts() {
        Map<String, Map<String, ChannelHandlerContext>> unmodifiableOuterMap = new HashMap<>();
        for (Map.Entry<String, Map<String, ChannelHandlerContext>> entry : deviceChannelCtxMap.entrySet()) {
            // 鍒涘缓鍐呴儴Map鐨勫壇鏈苟浣垮叾涓嶅彲淇敼
            Map<String, ChannelHandlerContext> unmodifiableInnerMap = Collections.unmodifiableMap(new HashMap<>(entry.getValue()));
            unmodifiableOuterMap.put(entry.getKey(), unmodifiableInnerMap);
        }
        return Collections.unmodifiableMap(unmodifiableOuterMap);
    }
}
