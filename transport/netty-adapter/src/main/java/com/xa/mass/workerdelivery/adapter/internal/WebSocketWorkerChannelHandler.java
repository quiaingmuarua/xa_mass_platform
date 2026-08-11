package com.xa.mass.workerdelivery.adapter.internal;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import java.util.Objects;
import java.util.function.BooleanSupplier;

final class WebSocketWorkerChannelHandler
        extends SimpleChannelInboundHandler<WebSocketFrame> {

    private final WorkerConnectionSessionFactory sessionFactory;
    private final BooleanSupplier acceptingConnections;
    private WorkerConnectionSession session;

    WebSocketWorkerChannelHandler(
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
    public void userEventTriggered(ChannelHandlerContext context, Object event) {
        if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            if (!acceptingConnections.getAsBoolean()) {
                WebSocketTextFrameStrategy.INSTANCE.close(
                        context.channel(),
                        ConnectionCloseReason.ADAPTER_STOPPING
                );
            }
            return;
        }
        context.fireUserEventTriggered(event);
    }

    @Override
    protected void channelRead0(
            ChannelHandlerContext context,
            WebSocketFrame frame
    ) {
        if (frame instanceof TextWebSocketFrame text) {
            session.onText(text.text());
        } else if (frame instanceof BinaryWebSocketFrame) {
            WebSocketTextFrameStrategy.INSTANCE.close(
                    context.channel(),
                    ConnectionCloseReason.BINARY_UNSUPPORTED
            );
        }
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
