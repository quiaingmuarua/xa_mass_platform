package com.xa.mass.workerdelivery.adapter.websocket;

import com.xa.mass.workerdelivery.adapter.application.WorkerConnection;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

final class NettyWebSocketWorkerConnection
        implements WorkerConnection {

    private final Channel channel;
    private final WorkerDeliveryCodec codec;
    private final long sendTimeLimitMillis;

    NettyWebSocketWorkerConnection(
            Channel channel,
            WorkerDeliveryCodec codec,
            Duration sendTimeLimit
    ) {
        this.channel = Objects.requireNonNull(channel, "channel");
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
    public CommandDeliveryAttempt deliver(
            WorkerCommandEnvelope command
    ) {
        Objects.requireNonNull(command, "command");
        if (!channel.isActive() || !channel.isWritable()) {
            return CommandDeliveryAttempt.REJECTED_BEFORE_SEND;
        }
        String encoded = codec.encodeWorkerCommand(command);
        ChannelFuture send;
        try {
            send = channel.writeAndFlush(new TextWebSocketFrame(encoded));
        } catch (RuntimeException error) {
            return CommandDeliveryAttempt.UNKNOWN;
        }
        try {
            if (!send.await(sendTimeLimitMillis, TimeUnit.MILLISECONDS)) {
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

    @Override
    public void close(WorkerConnectionCloseReason reason) {
        Objects.requireNonNull(reason, "reason");
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
        channel.writeAndFlush(new CloseWebSocketFrame(code, message))
                .addListener(ChannelFutureListener.CLOSE);
    }
}
