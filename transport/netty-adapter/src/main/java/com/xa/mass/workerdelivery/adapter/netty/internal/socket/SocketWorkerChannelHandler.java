package com.xa.mass.workerdelivery.adapter.netty.internal.socket;

import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionSession;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionSessionFactory;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.Objects;
import java.util.function.BooleanSupplier;

final class SocketWorkerChannelHandler
        extends SimpleChannelInboundHandler<String> {

    private final WorkerConnectionSessionFactory sessionFactory;
    private final BooleanSupplier acceptingConnections;
    private WorkerConnectionSession session;

    SocketWorkerChannelHandler(
            WorkerConnectionSessionFactory sessionFactory,
            BooleanSupplier acceptingConnections
    ) {
        this.sessionFactory = Objects.requireNonNull(
                sessionFactory,
                "sessionFactory"
        );
        this.acceptingConnections = Objects.requireNonNull(
                acceptingConnections,
                "acceptingConnections"
        );
    }

    @Override
    public void handlerAdded(ChannelHandlerContext context) {
        session = sessionFactory.create(context);
    }

    @Override
    public void channelActive(ChannelHandlerContext context) {
        if (acceptingConnections.getAsBoolean()) {
            context.fireChannelActive();
        } else {
            context.close();
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, String line) {
        session.onText(line);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        session.onInactive();
        context.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        session.onException();
    }
}
