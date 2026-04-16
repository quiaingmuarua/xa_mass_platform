package com.xa.mass.gateway.session;

import com.xa.mass.base.channel.eventbus.core.EventPublisher;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerOfflineEvent;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerOnlineEvent;
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

    public static final ServerSessionManager INSTANCE = new ServerSessionManager();

    private static final Logger logger = LoggerFactory.getLogger(ServerSessionManager.class);

    // workerId -> connRole -> Channel
    private final Map<String, Map<String, Channel>> workerChannelMap = new ConcurrentHashMap<>();

    // workerId -> connRole -> ChannelHandlerContext
    private final Map<String, Map<String, ChannelHandlerContext>> workerChannelCtxMap = new ConcurrentHashMap<>();

    // Reverse index: Channel -> [workerId, connRole]
    private final Map<Channel, WorkerConnKey> channelIndex = new ConcurrentHashMap<>();

    public synchronized void addSession(String workerId, String connRole, Channel channel, ChannelHandlerContext ctx) {
        Map<String, Channel> existingRoleMap = workerChannelMap.get(workerId);
        if (existingRoleMap != null) {
            Channel existingChannel = existingRoleMap.get(connRole);
            if (existingChannel != null && existingChannel == channel && existingChannel.isActive()) {
                logger.debug("Session for workerId={} role={} already exists and is active. Skipping add.", workerId, connRole);
                return;
            }
            if (existingChannel != null && existingChannel != channel) {
                logger.warn("Existing channel for workerId={} role={} found, but new channel is different. Replacing session.", workerId, connRole);
                removeSession(existingChannel);
            }
        }

        workerChannelMap
                .computeIfAbsent(workerId, key -> new ConcurrentHashMap<>())
                .put(connRole, channel);

        workerChannelCtxMap
                .computeIfAbsent(workerId, key -> new ConcurrentHashMap<>())
                .put(connRole, ctx);

        channelIndex.put(channel, new WorkerConnKey(workerId, connRole));

        logger.info("Connected: workerId={} role={} channelId={} totalWorkers={}",
                workerId, connRole, channel.id().asShortText(), workerChannelMap.size());
        EventPublisher.post(new WorkerOnlineEvent(workerId, "websocket connected", null));
    }

    public synchronized void removeSession(Channel channel) {
        WorkerConnKey key = channelIndex.remove(channel);
        if (key != null) {
            Map<String, Channel> roleMap = workerChannelMap.get(key.getWorkerId());
            if (roleMap != null) {
                if (channel.equals(roleMap.get(key.getConnRole()))) {
                    roleMap.remove(key.getConnRole());
                }
                if (roleMap.isEmpty()) {
                    workerChannelMap.remove(key.getWorkerId());
                }
            }

            Map<String, ChannelHandlerContext> roleCtxMap = workerChannelCtxMap.get(key.getWorkerId());
            if (roleCtxMap != null) {
                Channel remainingChannel = roleMap != null ? roleMap.get(key.getConnRole()) : null;
                if (remainingChannel == null) {
                    roleCtxMap.remove(key.getConnRole());
                }
                if (roleCtxMap.isEmpty()) {
                    workerChannelCtxMap.remove(key.getWorkerId());
                }
            }

            logger.info("Disconnected: workerId={} role={} channelId={}",
                    key.getWorkerId(), key.getConnRole(), channel.id().asShortText());
            EventPublisher.post(new WorkerOfflineEvent(key.getWorkerId(), "websocket disconnected", null));
        } else {
            logger.warn("Attempted to remove session for a channel not in index: {}", channel.id().asShortText());
        }
    }

    public boolean sendMessage(String workerId, String connRole, String message) {
        Map<String, Channel> roleMap = workerChannelMap.get(workerId);
        if (roleMap != null) {
            Channel channel = roleMap.get(connRole);
            if (channel != null && channel.isActive()) {
                channel.writeAndFlush(new TextWebSocketFrame(message));
                return true;
            }
        }
        logger.warn("Failed to send to worker={}, role={}. Channel not found or inactive.", workerId, connRole);
        return false;
    }

    public void broadcastMessage(String message) {
        TextWebSocketFrame frame = new TextWebSocketFrame(message);
        int sentCount = 0;
        for (Map<String, Channel> roleMap : workerChannelMap.values()) {
            for (Channel channel : roleMap.values()) {
                if (channel.isActive()) {
                    channel.writeAndFlush(frame.copy());
                    sentCount++;
                }
            }
        }
        logger.debug("Broadcast message sent to {} active channels.", sentCount);
    }

    public boolean isWorkerOnline(String workerId, String connRole) {
        Map<String, Channel> roleMap = workerChannelMap.get(workerId);
        if (roleMap == null) {
            return false;
        }
        Channel ch = roleMap.get(connRole);
        return ch != null && ch.isActive();
    }

    public int getWorkerConnectionCount() {
        return (int) channelIndex.keySet().stream().filter(Channel::isActive).count();
    }

    public WorkerConnKey getWorkerConnKey(Channel channel) {
        return channelIndex.get(channel);
    }

    public Channel getChannel(String workerId, String connRole) {
        Map<String, Channel> roleMap = workerChannelMap.get(workerId);
        return roleMap != null ? roleMap.get(connRole) : null;
    }

    public ChannelHandlerContext getChannelContext(String workerId, String connRole) {
        Map<String, ChannelHandlerContext> roleCtxMap = workerChannelCtxMap.get(workerId);
        return roleCtxMap != null ? roleCtxMap.get(connRole) : null;
    }

    public synchronized void shutdown() {
        logger.info("Shutting down session manager, closing {} worker connections...", workerChannelMap.size());
        for (Map<String, Channel> roleMap : workerChannelMap.values()) {
            for (Channel channel : roleMap.values()) {
                if (channel.isActive()) {
                    channel.close();
                }
            }
        }
        workerChannelMap.clear();
        workerChannelCtxMap.clear();
        channelIndex.clear();
        logger.info("Session manager shutdown complete.");
    }

    public Map<String, Map<String, Channel>> getAllWorkerChannels() {
        Map<String, Map<String, Channel>> unmodifiableOuterMap = new HashMap<>();
        for (Map.Entry<String, Map<String, Channel>> entry : workerChannelMap.entrySet()) {
            Map<String, Channel> unmodifiableInnerMap = Collections.unmodifiableMap(new HashMap<>(entry.getValue()));
            unmodifiableOuterMap.put(entry.getKey(), unmodifiableInnerMap);
        }
        return Collections.unmodifiableMap(unmodifiableOuterMap);
    }

    public Map<String, Map<String, ChannelHandlerContext>> getAllWorkerChannelContexts() {
        Map<String, Map<String, ChannelHandlerContext>> unmodifiableOuterMap = new HashMap<>();
        for (Map.Entry<String, Map<String, ChannelHandlerContext>> entry : workerChannelCtxMap.entrySet()) {
            Map<String, ChannelHandlerContext> unmodifiableInnerMap = Collections.unmodifiableMap(new HashMap<>(entry.getValue()));
            unmodifiableOuterMap.put(entry.getKey(), unmodifiableInnerMap);
        }
        return Collections.unmodifiableMap(unmodifiableOuterMap);
    }
}
