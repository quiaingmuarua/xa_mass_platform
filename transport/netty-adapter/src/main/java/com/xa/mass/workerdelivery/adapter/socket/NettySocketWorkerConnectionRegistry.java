package com.xa.mass.workerdelivery.adapter.socket;

import com.xa.mass.workerdelivery.adapter.dispatch.WorkerCommandDelivery;
import com.xa.mass.workerdelivery.adapter.dispatch.WorkerCommandDelivery.CommandDeliveryAttempt;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessageType;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

final class NettySocketWorkerConnectionRegistry
        implements WorkerCommandDelivery {

    private final Map<String, Channel> channels =
            new ConcurrentHashMap<>();
    private final WorkerDeliveryCodec codec;

    NettySocketWorkerConnectionRegistry(WorkerDeliveryCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    void bind(String workerId, Channel channel) {
        requireWorkerId(workerId);
        Objects.requireNonNull(channel, "channel");
        Channel previous = channels.put(workerId, channel);
        if (previous != null && previous != channel) {
            closeChannel(previous);
        }
    }

    void unbind(String workerId, Channel channel) {
        requireWorkerId(workerId);
        Objects.requireNonNull(channel, "channel");
        channels.computeIfPresent(workerId, (ignored, current) ->
                current == channel ? null : current
        );
    }

    @Override
    public CommandDeliveryAttempt deliver(
            String workerId,
            WorkerCommandEnvelope command
    ) {
        requireWorkerId(workerId);
        Objects.requireNonNull(command, "command");
        Channel current = channels.get(workerId);
        if (current == null) {
            return CommandDeliveryAttempt.RETRY_LATER;
        }
        if (!current.isActive()) {
            removeAndClose(workerId, current);
            return CommandDeliveryAttempt.RETRY_LATER;
        }
        if (!current.isWritable()) {
            return CommandDeliveryAttempt.RETRY_LATER;
        }

        String encoded = codec.encodeWorkerConnectionMessage(
                new WorkerConnectionMessage(
                        WorkerConnectionMessageType
                                .TASK_ITEM_COMMAND.name(),
                        codec.encodeWorkerCommand(command)
                )
        ) + "\n";
        ChannelFuture send;
        try {
            send = current.writeAndFlush(encoded);
        } catch (RuntimeException error) {
            removeAndClose(workerId, current);
            return CommandDeliveryAttempt.UNKNOWN;
        }
        send.addListener(future -> {
            if (!future.isSuccess()) {
                removeAndClose(workerId, current);
            }
        });
        return CommandDeliveryAttempt.STARTED;
    }

    void close(String workerId, Channel channel) {
        requireWorkerId(workerId);
        Objects.requireNonNull(channel, "channel");
        removeAndClose(workerId, channel);
    }

    void closeAll() {
        channels.forEach(this::removeAndClose);
    }

    int activeConnectionCount() {
        return channels.size();
    }

    private void removeAndClose(String workerId, Channel channel) {
        if (channels.remove(workerId, channel)) {
            closeChannel(channel);
        }
    }

    private static void closeChannel(Channel channel) {
        try {
            channel.close();
        } catch (RuntimeException ignored) {
            // Channel teardown is best effort.
        }
    }

    private static void requireWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException(
                    "workerId must be non-blank"
            );
        }
    }
}
