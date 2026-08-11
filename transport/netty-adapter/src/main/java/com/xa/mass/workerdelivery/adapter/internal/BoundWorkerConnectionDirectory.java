package com.xa.mass.workerdelivery.adapter.internal;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

final class BoundWorkerConnectionDirectory implements DeliveryCommandTarget {

    private final Map<String, BoundWorkerConnection> connections =
            new ConcurrentHashMap<>();
    private final WorkerDeliveryCodec codec;

    BoundWorkerConnectionDirectory(WorkerDeliveryCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    void activate(
            String workerId,
            Channel channel,
            TextFrameStrategy frameStrategy
    ) {
        requireWorkerId(workerId);
        BoundWorkerConnection connection = new BoundWorkerConnection(
                Objects.requireNonNull(channel, "channel"),
                Objects.requireNonNull(frameStrategy, "frameStrategy")
        );
        BoundWorkerConnection previous = connections.put(
                workerId,
                connection
        );
        if (previous != null && previous.channel() != channel) {
            previous.close(ConnectionCloseReason.REPLACED);
        }
    }

    void deactivate(String workerId, Channel channel) {
        requireWorkerId(workerId);
        Objects.requireNonNull(channel, "channel");
        connections.computeIfPresent(workerId, (ignored, current) ->
                current.channel() == channel ? null : current
        );
    }

    @Override
    public DeliveryAttempt deliver(
            String workerId,
            DeliveryCommand command
    ) {
        requireWorkerId(workerId);
        Objects.requireNonNull(command, "command");
        BoundWorkerConnection current = connections.get(workerId);
        if (current == null) {
            return DeliveryAttempt.RETRY_LATER;
        }
        Channel channel = current.channel();
        if (!channel.isActive()) {
            removeAndClose(
                    workerId,
                    current,
                    ConnectionCloseReason.TRANSPORT_ERROR
            );
            return DeliveryAttempt.RETRY_LATER;
        }
        if (!channel.isWritable()) {
            return DeliveryAttempt.RETRY_LATER;
        }

        ChannelFuture send;
        try {
            send = current.frameStrategy().writeText(
                    channel,
                    codec.encodeDeliveryCommand(command)
            );
        } catch (RuntimeException error) {
            removeAndClose(
                    workerId,
                    current,
                    ConnectionCloseReason.TRANSPORT_ERROR
            );
            return DeliveryAttempt.UNKNOWN;
        }
        send.addListener(future -> {
            if (!future.isSuccess()) {
                removeAndClose(
                        workerId,
                        current,
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
        BoundWorkerConnection current = connections.get(workerId);
        if (current != null && current.channel() == channel) {
            removeAndClose(workerId, current, reason);
        } else {
            closeUntracked(channel, reason);
        }
    }

    int activeConnectionCount() {
        return connections.size();
    }

    private void removeAndClose(
            String workerId,
            BoundWorkerConnection connection,
            ConnectionCloseReason reason
    ) {
        if (connections.remove(workerId, connection)) {
            connection.close(reason);
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

    private record BoundWorkerConnection(
            Channel channel,
            TextFrameStrategy frameStrategy
    ) {
        private void close(ConnectionCloseReason reason) {
            frameStrategy.close(channel, reason);
        }
    }
}
