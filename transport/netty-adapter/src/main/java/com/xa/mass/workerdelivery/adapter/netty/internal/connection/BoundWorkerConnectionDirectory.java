package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import com.xa.mass.workerdelivery.adapter.netty.internal.gateway.DeliveryCommandTarget;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class BoundWorkerConnectionDirectory
        implements DeliveryCommandTarget {

    private final Map<String, Channel> connections =
            new ConcurrentHashMap<>();
    private final WorkerDeliveryCodec codec;
    private final TextFrameStrategy frameStrategy;

    public BoundWorkerConnectionDirectory(
            WorkerDeliveryCodec codec,
            TextFrameStrategy frameStrategy
    ) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.frameStrategy = Objects.requireNonNull(
                frameStrategy,
                "frameStrategy"
        );
    }

    void activate(String workerId, Channel channel) {
        requireWorkerId(workerId);
        Channel requiredChannel = Objects.requireNonNull(channel, "channel");
        Channel previous = connections.put(workerId, requiredChannel);
        if (previous != null && previous != requiredChannel) {
            frameStrategy.close(previous, ConnectionCloseReason.REPLACED);
        }
    }

    void deactivate(String workerId, Channel channel) {
        requireWorkerId(workerId);
        Objects.requireNonNull(channel, "channel");
        connections.computeIfPresent(workerId, (ignored, current) ->
                current == channel ? null : current
        );
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
            removeAndClose(
                    workerId,
                    channel,
                    ConnectionCloseReason.TRANSPORT_ERROR
            );
            return DeliveryAttempt.RETRY_LATER;
        }
        if (!channel.isWritable()) {
            return DeliveryAttempt.RETRY_LATER;
        }

        ChannelFuture send;
        try {
            send = frameStrategy.writeText(
                    channel,
                    codec.encodeDeliveryCommand(command)
            );
        } catch (RuntimeException error) {
            removeAndClose(
                    workerId,
                    channel,
                    ConnectionCloseReason.TRANSPORT_ERROR
            );
            return DeliveryAttempt.UNKNOWN;
        }
        send.addListener(future -> {
            if (!future.isSuccess()) {
                removeAndClose(
                        workerId,
                        channel,
                        ConnectionCloseReason.TRANSPORT_ERROR
                );
            }
        });
        return DeliveryAttempt.STARTED;
    }

    void close(
            String workerId,
            Channel channel,
            ConnectionCloseReason reason
    ) {
        requireWorkerId(workerId);
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(reason, "reason");
        Channel current = connections.get(workerId);
        if (current == channel) {
            removeAndClose(workerId, current, reason);
        } else {
            closeUntracked(channel, reason);
        }
    }

    public int activeConnectionCount() {
        return connections.size();
    }

    private void removeAndClose(
            String workerId,
            Channel channel,
            ConnectionCloseReason reason
    ) {
        if (connections.remove(workerId, channel)) {
            frameStrategy.close(channel, reason);
        }
    }

    private static void closeUntracked(
            Channel channel,
            ConnectionCloseReason reason
    ) {
        if (!channel.isOpen()) {
            return;
        }
        try {
            channel.close();
        } catch (RuntimeException ignored) {
            // A superseded or already-detached Channel is best effort.
        }
    }

    private static void requireWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must be non-blank");
        }
    }
}
