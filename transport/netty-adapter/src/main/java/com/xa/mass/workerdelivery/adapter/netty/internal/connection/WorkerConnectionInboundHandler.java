package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.Objects;

/**
 * Netty callback adapter for one shared Worker connection mechanism.
 *
 * <p>This handler owns no connection semantics or connection-local state. It
 * only translates Netty callbacks into explicit mechanism operations.
 */
@ChannelHandler.Sharable
public final class WorkerConnectionInboundHandler
        extends SimpleChannelInboundHandler<String> {

    private final WorkerConnectionMechanism mechanism;

    public WorkerConnectionInboundHandler(
            WorkerConnectionMechanism mechanism
    ) {
        this.mechanism = Objects.requireNonNull(mechanism, "mechanism");
    }

    @Override
    protected void channelRead0(
            ChannelHandlerContext context,
            String text
    ) {
        mechanism.receive(context, text);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        try {
            mechanism.channelInactive(context.channel());
        } finally {
            context.fireChannelInactive();
        }
    }

    @Override
    public void exceptionCaught(
            ChannelHandlerContext context,
            Throwable failure
    ) {
        mechanism.channelFailed(context.channel(), failure);
    }
}
