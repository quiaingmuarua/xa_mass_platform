package com.xa.mass.workerdelivery.adapter.netty.internal.network;

import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionHandlerFactory;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.LineEncoder;
import io.netty.handler.codec.string.LineSeparator;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

final class SocketNetworkProtocol implements AdapterNetworkProtocol {

    private static final int MAX_FRAME_BYTES = 1_048_576;

    private final Duration sendTimeLimit;

    SocketNetworkProtocol(Duration sendTimeLimit) {
        this.sendTimeLimit = Objects.requireNonNull(
                sendTimeLimit,
                "sendTimeLimit"
        );
    }

    @Override
    public void installPipeline(
            SocketChannel channel,
            WorkerConnectionHandlerFactory handlers,
            BooleanSupplier acceptingConnections
    ) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(handlers, "handlers");
        Objects.requireNonNull(acceptingConnections, "acceptingConnections");
        channel.pipeline()
                .addLast(new LineBasedFrameDecoder(
                        MAX_FRAME_BYTES,
                        true,
                        true
                ))
                .addLast(new StringDecoder(StandardCharsets.UTF_8))
                .addLast(new LineEncoder(
                        LineSeparator.UNIX,
                        StandardCharsets.UTF_8
                ))
                .addLast(new WriteTimeoutHandler(
                        sendTimeLimit.toMillis(),
                        TimeUnit.MILLISECONDS
                ))
                .addLast(new ConnectionAdmissionHandler(
                        this,
                        acceptingConnections
                ))
                .addLast(handlers.newIdentityHandler());
    }

    @Override
    public void close(Channel channel, AdapterConnectionCloseReason reason) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(reason, "reason");
        if (!channel.isOpen()) {
            return;
        }
        try {
            channel.close();
        } catch (RuntimeException ignored) {
            // Physical Channel teardown is best effort.
        }
    }

    private static final class ConnectionAdmissionHandler
            extends ChannelInboundHandlerAdapter {

        private final AdapterNetworkProtocol protocol;
        private final BooleanSupplier acceptingConnections;

        private ConnectionAdmissionHandler(
                AdapterNetworkProtocol protocol,
                BooleanSupplier acceptingConnections
        ) {
            this.protocol = protocol;
            this.acceptingConnections = acceptingConnections;
        }

        @Override
        public void channelActive(ChannelHandlerContext context) {
            if (acceptingConnections.getAsBoolean()) {
                context.fireChannelActive();
            } else {
                protocol.close(
                        context.channel(),
                        AdapterConnectionCloseReason.ADAPTER_STOPPING
                );
            }
        }
    }
}
