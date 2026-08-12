package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import com.xa.mass.workerdelivery.adapter.netty.internal.gateway.DeliveryCommandTarget;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.AdapterConnectionCloseReason;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.AdapterNetworkProtocol;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Process-local verified, pending, and active Worker route owner. */
public final class WorkerRouteDirectory implements DeliveryCommandTarget {

    private final Object routeLock = new Object();
    private final Set<String> verifiedWorkerIds = new HashSet<>();
    private final Map<String, Channel> pendingVerifications = new HashMap<>();
    private final ConcurrentMap<String, Channel> activeChannels =
            new ConcurrentHashMap<>();
    private final WorkerDeliveryCodec codec;
    private final AdapterNetworkProtocol networkProtocol;

    public WorkerRouteDirectory(
            WorkerDeliveryCodec codec,
            AdapterNetworkProtocol networkProtocol
    ) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.networkProtocol = Objects.requireNonNull(
                networkProtocol,
                "networkProtocol"
        );
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
            if (!pendingVerifications.remove(workerId, expectedChannel)) {
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
                    AdapterConnectionCloseReason.TRANSPORT_ERROR
            );
            return DeliveryAttempt.RETRY_LATER;
        }
        if (!channel.isWritable()) {
            return DeliveryAttempt.RETRY_LATER;
        }

        ChannelFuture send;
        try {
            send = channel.writeAndFlush(
                    codec.encodeDeliveryCommand(command)
            );
        } catch (RuntimeException error) {
            removeAndClose(
                    workerId,
                    channel,
                    AdapterConnectionCloseReason.TRANSPORT_ERROR
            );
            return DeliveryAttempt.UNKNOWN;
        }
        send.addListener(future -> {
            if (!future.isSuccess()) {
                removeAndClose(
                        workerId,
                        channel,
                        AdapterConnectionCloseReason.TRANSPORT_ERROR
                );
            }
        });
        return DeliveryAttempt.STARTED;
    }

    void close(
            String workerId,
            Channel expectedChannel,
            AdapterConnectionCloseReason reason
    ) {
        requireWorkerId(workerId);
        Objects.requireNonNull(expectedChannel, "expectedChannel");
        Objects.requireNonNull(reason, "reason");
        activeChannels.remove(workerId, expectedChannel);
        networkProtocol.close(expectedChannel, reason);
    }

    void closeUnbound(
            Channel channel,
            AdapterConnectionCloseReason reason
    ) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(reason, "reason");
        networkProtocol.close(channel, reason);
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
            AdapterConnectionCloseReason reason
    ) {
        activeChannels.remove(workerId, expectedChannel);
        networkProtocol.close(expectedChannel, reason);
    }

    private void closeReplaced(Channel previous, Channel replacement) {
        if (previous != null && previous != replacement) {
            networkProtocol.close(
                    previous,
                    AdapterConnectionCloseReason.REPLACED
            );
        }
    }

    private static void requireWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must be non-blank");
        }
    }
}
