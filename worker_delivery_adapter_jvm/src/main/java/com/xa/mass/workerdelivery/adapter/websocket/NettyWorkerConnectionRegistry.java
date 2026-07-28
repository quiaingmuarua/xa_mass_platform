package com.xa.mass.workerdelivery.adapter.websocket;

import com.xa.mass.workerdelivery.adapter.websocket.WorkerConnectionRegistry.CommandDeliveryAttempt;
import com.xa.mass.workerdelivery.adapter.websocket.WorkerConnectionRegistry.ConnectionCloseReason;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

final class NettyWorkerConnectionRegistry
        implements WorkerConnectionRegistry {

    private final Map<String, Channel> channels =
            new ConcurrentHashMap<>();
    private final WorkerDeliveryCodec codec;
    private final long sendTimeLimitMillis;

    NettyWorkerConnectionRegistry(
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

    @Override
    public void bind(
            String workerId,
            Channel channel
    ) {
        requireWorkerId(workerId);
        Objects.requireNonNull(channel, "channel");
        Channel previous = channels.put(workerId, channel);
        if (previous != null && previous != channel) {
            closeChannel(previous, ConnectionCloseReason.REPLACED);
        }
    }

    @Override
    public void unbind(
            String workerId,
            Channel channel
    ) {
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
            removeAndClose(
                    workerId,
                    current,
                    ConnectionCloseReason.TRANSPORT_ERROR
            );
        }
        return attempt;
    }

    @Override
    public void close(
            String workerId,
            Channel channel,
            ConnectionCloseReason reason
    ) {
        requireWorkerId(workerId);
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(reason, "reason");
        unbind(workerId, channel);
        closeChannel(channel, reason);
    }

    @Override
    public void closeAll(ConnectionCloseReason reason) {
        Objects.requireNonNull(reason, "reason");
        channels.forEach((workerId, current) ->
                removeAndClose(workerId, current, reason)
        );
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
        String encoded = codec.encodeWorkerCommand(command);
        ChannelFuture send;
        try {
            send = channel.writeAndFlush(
                    new TextWebSocketFrame(encoded)
            );
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

    private void removeAndClose(
            String workerId,
            Channel channel,
            ConnectionCloseReason reason
    ) {
        if (channels.remove(workerId, channel)) {
            closeChannel(channel, reason);
        }
    }

    private static void closeChannel(
            Channel channel,
            ConnectionCloseReason reason
    ) {
        if (!channel.isOpen()) {
            return;
        }
        int code = switch (reason) {
            case REPLACED -> 1008;
            case RESULT_BUFFER_FULL -> 1013;
            case TRANSPORT_ERROR -> 1011;
            case ADAPTER_STOPPING -> 1001;
        };
        String message = switch (reason) {
            case REPLACED -> "Replaced by a newer Worker connection";
            case RESULT_BUFFER_FULL -> "Worker result buffer is full";
            case TRANSPORT_ERROR -> "Worker transport failed";
            case ADAPTER_STOPPING -> "Adapter is stopping";
        };
        try {
            channel.writeAndFlush(new CloseWebSocketFrame(code, message))
                    .addListener(ChannelFutureListener.CLOSE);
        } catch (RuntimeException ignored) {
            try {
                channel.close();
            } catch (RuntimeException closeIgnored) {
                // Channel teardown is best effort.
            }
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
