package com.xa.mass.transport.websocket.server;

import com.xa.mass.transport.websocket.queue.WebSocketTransportFrameCodec;
import com.xa.mass.transport.websocket.dispatcher.WebSocketInboundMessageSink;
import com.xa.mass.transport.websocket.session.ServerSessionManager;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Netty WebSocket transport ingress for the current WebSocket adapter.
 *
 * <p>This server should behave like a transport router: accept connections,
 * frame inbound text messages, and hand them to the dispatcher/runtime
 * pipeline. It must not grow into the place where business/control payloads
 * are interpreted.
 */
public class WebSocketServerImpl implements MassWebSocketServer {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketServerImpl.class);

    private final AtomicLong activeConnections = new AtomicLong(0);
    private final int port;
    private final String websocketPath;
    private final ServerSessionManager sessionManager;
    private final WebSocketTransportFrameCodec frameCodec;
    private final WebSocketInboundMessageSink inboundMessageSink;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private volatile boolean running = false;

    public WebSocketServerImpl(int port,
                               String websocketPath,
                               WebSocketTransportFrameCodec frameCodec,
                               WebSocketInboundMessageSink inboundMessageSink,
                               ServerSessionManager sessionManager) {
        this.port = port;
        this.websocketPath = websocketPath;
        this.frameCodec = frameCodec;
        this.inboundMessageSink = inboundMessageSink;
        this.sessionManager = sessionManager;
    }

    @Override
    public void start() {
        MDC.clear();
        validateConfiguration();
        this.running = true;

        boolean useEpoll = Epoll.isAvailable();
        logger.info("Netty Epoll available: {}", useEpoll);

        bossGroup = useEpoll
                ? new EpollEventLoopGroup(1, new DefaultThreadFactory("boss"))
                : new NioEventLoopGroup(1, new DefaultThreadFactory("boss"));
        workerGroup = useEpoll
                ? new EpollEventLoopGroup(0, new DefaultThreadFactory("worker"))
                : new NioEventLoopGroup(0, new DefaultThreadFactory("worker"));

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(useEpoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.SO_RCVBUF, 512 * 1024)
                    .childOption(ChannelOption.SO_SNDBUF, 512 * 1024);

            if (useEpoll) {
                bootstrap.option(EpollChannelOption.SO_REUSEPORT, true);
            }

            bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel channel) {
                    ChannelPipeline pipeline = channel.pipeline();
                    pipeline.addLast(new HttpServerCodec());
                    pipeline.addLast(new HttpObjectAggregator(65536));
                    pipeline.addLast(new WebSocketServerProtocolHandler(
                            websocketPath,
                            null,
                            true,
                            65536 * 10,
                            false,
                            true,
                            10000L));
                    pipeline.addLast(new ConnectionStatsHandler());
                    pipeline.addLast(new DispatcherInboundHandler(frameCodec, inboundMessageSink, sessionManager));
                }
            });

            ChannelFuture future = bootstrap.bind(this.port).sync();
            if (future.isSuccess()) {
                logger.info("WebSocket server started on port {} with path {}", this.port, websocketPath);
            } else {
                logger.error("Failed to start WebSocket server on port {}", this.port, future.cause());
                stop();
            }
        } catch (InterruptedException e) {
            logger.error("Server startup interrupted", e);
            Thread.currentThread().interrupt();
            stop();
            throw new IllegalStateException("WebSocket server startup interrupted", e);
        } catch (Exception e) {
            logger.error("Unexpected error during server startup", e);
            stop();
            throw new IllegalStateException("WebSocket server startup failed", e);
        }
    }

    @PreDestroy
    @Override
    public void stop() {
        MDC.clear();
        running = false;
        logger.info("Shutting down WebSocket server...");
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
        }
        logger.info("WebSocket server shutdown complete.");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public Channel getClientChannel(String clientId) {
        if (sessionManager == null) {
            return null;
        }
        return sessionManager.getChannel(clientId);
    }

    public long getActiveConnectionCount() {
        return activeConnections.get();
    }

    private void validateConfiguration() {
        if (port < 0) {
            throw new IllegalStateException("WebSocket server requires a non-negative port");
        }
        if (websocketPath == null || websocketPath.isBlank()) {
            throw new IllegalStateException("WebSocket server requires a non-blank websocketPath");
        }
        Objects.requireNonNull(frameCodec, "WebSocket server requires frameCodec");
        Objects.requireNonNull(inboundMessageSink, "WebSocket server requires inboundMessageSink");
        Objects.requireNonNull(sessionManager, "WebSocket server requires sessionManager");
    }

    private class ConnectionStatsHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            long count = activeConnections.incrementAndGet();
            logger.debug("Connection opened: {}, total={}", ctx.channel().remoteAddress(), count);
            super.channelActive(ctx);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            long count = activeConnections.decrementAndGet();
            logger.debug("Connection closed: {}, total={}", ctx.channel().remoteAddress(), count);
            // Remove the session before propagating channelInactive so worker offline state
            // and future dispatch decisions observe the closed channel consistently.
            if (sessionManager != null) {
                sessionManager.removeSession(ctx.channel());
            }
            super.channelInactive(ctx);
        }
    }
}
