package com.xa.mass.workerdelivery.adapter.socket;

import com.xa.mass.workerdelivery.adapter.dispatch.WorkerCommandDelivery;
import com.xa.mass.workerdelivery.adapter.dispatch.WorkerCommandDelivery.CommandDeliveryAttempt;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.TaskItemCommandMessage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

final class NettySocketWorkerConnectionRegistry
        implements WorkerCommandDelivery {

    private final Map<String, Channel> channels =
            new ConcurrentHashMap<>();
    private final WorkerDeliveryCodec codec;
    private final long sendTimeLimitMillis;

    NettySocketWorkerConnectionRegistry(
            WorkerDeliveryCodec codec,
            Duration sendTimeLimit
    ) {
        this.codec = Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(sendTimeLimit, "sendTimeLimit");
        if (sendTimeLimit.isZero()
                || sendTimeLimit.isNegative()
                || sendTimeLimit.toMillis() <= 0) {
            throw new IllegalArgumentException(
                    "sendTimeLimit must be positive"
            );
        }
        sendTimeLimitMillis = sendTimeLimit.toMillis();
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
            return CommandDeliveryAttempt.REJECTED_BEFORE_SEND;
        }
        CommandDeliveryAttempt attempt = deliver(current, command);
        if (attempt != CommandDeliveryAttempt.DELIVERED) {
            removeAndClose(workerId, current);
        }
        return attempt;
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

    private CommandDeliveryAttempt deliver(
            Channel channel,
            WorkerCommandEnvelope command
    ) {
        if (!channel.isActive() || !channel.isWritable()) {
            return CommandDeliveryAttempt.REJECTED_BEFORE_SEND;
        }
        String encoded = codec.encodeWorkerConnectionMessage(
                new TaskItemCommandMessage(command)
        ) + "\n";
        ChannelFuture send;
        try {
            send = channel.writeAndFlush(encoded);
        } catch (RuntimeException error) {
            return CommandDeliveryAttempt.UNKNOWN;
        }
        try {
            if (!send.await(
                    sendTimeLimitMillis,
                    TimeUnit.MILLISECONDS
            )) {
                return CommandDeliveryAttempt.UNKNOWN;
            }
            return send.isSuccess()
                    ? CommandDeliveryAttempt.DELIVERED
                    : CommandDeliveryAttempt.UNKNOWN;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return CommandDeliveryAttempt.UNKNOWN;
        }
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
