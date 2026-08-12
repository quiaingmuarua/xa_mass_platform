package com.xa.mass.workerdelivery.adapter.netty.internal.websocket;

import com.xa.mass.workerdelivery.adapter.netty.internal.gateway.DeliveryCommandTarget;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class WebSocketWorkerRouteDirectory
        implements DeliveryCommandTarget {

    private final Object routeLock = new Object();
    private final Set<String> verifiedWorkerIds = new HashSet<>();
    private final Map<String, Channel> pendingVerifications = new HashMap<>();
    private final ConcurrentMap<String, Channel> activeChannels =
            new ConcurrentHashMap<>();
    private final WorkerDeliveryCodec codec;

    public WebSocketWorkerRouteDirectory(WorkerDeliveryCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    boolean isRouteVerified(String workerId) {
        requireWorkerId(workerId);
        synchronized (routeLock) {
            return verifiedWorkerIds.contains(workerId);
        }
    }

    boolean beginVerification(String workerId, Channel channel) {
        requireWorkerId(workerId);
        Channel requiredChannel = Objects.requireNonNull(channel, "channel");
        synchronized (routeLock) {
            if (verifiedWorkerIds.contains(workerId)
                    || pendingVerifications.containsKey(workerId)) {
                return false;
            }
            pendingVerifications.put(workerId, requiredChannel);
            return true;
        }
    }

    boolean isVerificationPending(
            String workerId,
            Channel expectedChannel
    ) {
        requireWorkerId(workerId);
        Objects.requireNonNull(expectedChannel, "expectedChannel");
        synchronized (routeLock) {
            return pendingVerifications.get(workerId) == expectedChannel;
        }
    }

    boolean completeVerificationAndActivate(
            String workerId,
            Channel expectedChannel
    ) {
        requireWorkerId(workerId);
        Objects.requireNonNull(expectedChannel, "expectedChannel");
        Channel previous;
        synchronized (routeLock) {
            if (!pendingVerifications.remove(
                    workerId,
                    expectedChannel
            )) {
                return false;
            }
            verifiedWorkerIds.add(workerId);
            previous = activeChannels.put(workerId, expectedChannel);
        }
        closeReplaced(previous, expectedChannel);
        return true;
    }

    void cancelVerification(String workerId, Channel expectedChannel) {
        requireWorkerId(workerId);
        Objects.requireNonNull(expectedChannel, "expectedChannel");
        synchronized (routeLock) {
            pendingVerifications.remove(workerId, expectedChannel);
        }
    }

    boolean activateIfVerified(String workerId, Channel channel) {
        requireWorkerId(workerId);
        Channel requiredChannel = Objects.requireNonNull(channel, "channel");
        Channel previous;
        synchronized (routeLock) {
            if (!verifiedWorkerIds.contains(workerId)) {
                return false;
            }
            previous = activeChannels.put(workerId, requiredChannel);
        }
        closeReplaced(previous, requiredChannel);
        return true;
    }

    void deactivate(String workerId, Channel expectedChannel) {
        requireWorkerId(workerId);
        Objects.requireNonNull(expectedChannel, "expectedChannel");
        activeChannels.remove(workerId, expectedChannel);
    }

    @Override
    public DeliveryAttempt deliver(
            String workerId,
            DeliveryCommand command
    ) {
        requireWorkerId(workerId);
        Objects.requireNonNull(command, "command");
        Channel channel = activeChannels.get(workerId);
        if (channel == null) {
            return DeliveryAttempt.RETRY_LATER;
        }
        if (!channel.isActive()) {
            removeAndClose(
                    workerId,
                    channel,
                    WebSocketCloseReason.TRANSPORT_ERROR
            );
            return DeliveryAttempt.RETRY_LATER;
        }
        if (!channel.isWritable()) {
            return DeliveryAttempt.RETRY_LATER;
        }

        ChannelFuture send;
        try {
            send = channel.writeAndFlush(new TextWebSocketFrame(
                    codec.encodeDeliveryCommand(command)
            ));
        } catch (RuntimeException error) {
            removeAndClose(
                    workerId,
                    channel,
                    WebSocketCloseReason.TRANSPORT_ERROR
            );
            return DeliveryAttempt.UNKNOWN;
        }
        send.addListener(future -> {
            if (!future.isSuccess()) {
                removeAndClose(
                        workerId,
                        channel,
                        WebSocketCloseReason.TRANSPORT_ERROR
                );
            }
        });
        return DeliveryAttempt.STARTED;
    }

    void close(
            String workerId,
            Channel expectedChannel,
            WebSocketCloseReason reason
    ) {
        requireWorkerId(workerId);
        Objects.requireNonNull(expectedChannel, "expectedChannel");
        Objects.requireNonNull(reason, "reason");
        activeChannels.remove(workerId, expectedChannel);
        reason.close(expectedChannel);
    }

    public int activeConnectionCount() {
        return activeChannels.size();
    }

    int verifiedWorkerCount() {
        synchronized (routeLock) {
            return verifiedWorkerIds.size();
        }
    }

    int pendingVerificationCount() {
        synchronized (routeLock) {
            return pendingVerifications.size();
        }
    }

    public void clear() {
        synchronized (routeLock) {
            verifiedWorkerIds.clear();
            pendingVerifications.clear();
            activeChannels.clear();
        }
    }

    private void removeAndClose(
            String workerId,
            Channel expectedChannel,
            WebSocketCloseReason reason
    ) {
        activeChannels.remove(workerId, expectedChannel);
        reason.close(expectedChannel);
    }

    private static void closeReplaced(
            Channel previous,
            Channel replacement
    ) {
        if (previous != null && previous != replacement) {
            WebSocketCloseReason.REPLACED.close(previous);
        }
    }

    private static void requireWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must be non-blank");
        }
    }
}
