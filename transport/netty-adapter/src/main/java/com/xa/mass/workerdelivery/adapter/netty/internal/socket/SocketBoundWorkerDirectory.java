package com.xa.mass.workerdelivery.adapter.netty.internal.socket;

import com.xa.mass.workerdelivery.adapter.netty.internal.gateway.DeliveryCommandTarget;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class SocketBoundWorkerDirectory
        implements DeliveryCommandTarget {

    private final ConcurrentMap<String, Channel> connections =
            new ConcurrentHashMap<>();
    private final WorkerDeliveryCodec codec;

    public SocketBoundWorkerDirectory(WorkerDeliveryCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    void activate(String workerId, Channel channel) {
        requireWorkerId(workerId);
        Channel requiredChannel = Objects.requireNonNull(channel, "channel");
        Channel previous = connections.put(workerId, requiredChannel);
        if (previous != null && previous != requiredChannel) {
            closeBestEffort(previous);
        }
    }

    void deactivate(String workerId, Channel expectedChannel) {
        requireWorkerId(workerId);
        Objects.requireNonNull(expectedChannel, "expectedChannel");
        connections.remove(workerId, expectedChannel);
    }

    @Override
    public DeliveryAttempt deliver(
            String workerId,
            DeliveryCommand command
    ) {
        requireWorkerId(workerId);
        Objects.requireNonNull(command, "command");
        Channel channel = connections.get(workerId);
        if (channel == null) {
            return DeliveryAttempt.RETRY_LATER;
        }
        if (!channel.isActive()) {
            removeAndClose(workerId, channel);
            return DeliveryAttempt.RETRY_LATER;
        }
        if (!channel.isWritable()) {
            return DeliveryAttempt.RETRY_LATER;
        }

        ChannelFuture send;
        try {
            send = channel.writeAndFlush(
                    codec.encodeDeliveryCommand(command) + "\n"
            );
        } catch (RuntimeException error) {
            removeAndClose(workerId, channel);
            return DeliveryAttempt.UNKNOWN;
        }
        send.addListener(future -> {
            if (!future.isSuccess()) {
                removeAndClose(workerId, channel);
            }
        });
        return DeliveryAttempt.STARTED;
    }

    void close(String workerId, Channel expectedChannel) {
        requireWorkerId(workerId);
        Objects.requireNonNull(expectedChannel, "expectedChannel");
        connections.remove(workerId, expectedChannel);
        closeBestEffort(expectedChannel);
    }

    public int activeConnectionCount() {
        return connections.size();
    }

    private void removeAndClose(String workerId, Channel expectedChannel) {
        connections.remove(workerId, expectedChannel);
        closeBestEffort(expectedChannel);
    }

    static void closeBestEffort(Channel channel) {
        if (!channel.isOpen()) {
            return;
        }
        try {
            channel.close();
        } catch (RuntimeException ignored) {
            // Physical Channel teardown is best effort.
        }
    }

    private static void requireWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must be non-blank");
        }
    }
}
