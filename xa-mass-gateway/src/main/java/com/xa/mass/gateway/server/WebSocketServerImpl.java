package com.xa.mass.gateway.server;

import com.xa.mass.gateway.queue.MessageCodec;
import com.xa.mass.gateway.session.ServerSessionManager;
import com.xa.mass.gateway.session.SessionRoles;
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
import java.util.function.Consumer;

/**
 * Netty WebSocket transport ingress for the current gateway adapter.
 *
 * <p>This server should behave like a transport router: accept connections,
 * frame inbound text messages, and hand them to the dispatcher/runtime
 * pipeline. It must not grow into the place where business/control payloads
 * are interpreted.
 */
public class WebSocketServerImpl implements MassWebSocketServer {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketServerImpl.class);

    private final AtomicLong activeConnections = new AtomicLong(0);
    private int port;
    private String websocketPath;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private volatile boolean running = false;
    private ServerSessionManager sessionManager;
    private MessageCodec messageCodec;
    private Consumer<String> inboundMessageSink;

    public WebSocketServerImpl() {
    }

    public WebSocketServerImpl(String websocketPath,
                               MessageCodec messageCodec,
                               Consumer<String> inboundMessageSink,
                               ServerSessionManager sessionManager) {
        this.websocketPath = websocketPath;
        this.messageCodec = messageCodec;
        this.inboundMessageSink = inboundMessageSink;
        this.sessionManager = sessionManager;
    }

    @Override
    public void start(int port) {
        MDC.clear();
        validateConfiguration();
        this.port = port;
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
                    pipeline.addLast(new DispatcherInboundHandler(messageCodec, inboundMessageSink, sessionManager));
                }
            });

            ChannelFuture future = bootstrap.bind(port).sync();
            if (future.isSuccess()) {
                logger.info("WebSocket server started on port {} with path {}", port, websocketPath);
            } else {
                logger.error("Failed to start WebSocket server on port {}", port, future.cause());
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
        return sessionManager.getChannel(clientId, SessionRoles.TASK_MESSAGES);
    }

    public long getActiveConnectionCount() {
        return activeConnections.get();
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void setWebsocketPath(String websocketPath) {
        this.websocketPath = websocketPath;
    }

    public void setMessageCodec(MessageCodec messageCodec) {
        this.messageCodec = messageCodec;
    }

    public void setInboundMessageSink(Consumer<String> inboundMessageSink) {
        this.inboundMessageSink = inboundMessageSink;
    }

    public void setSessionManager(ServerSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    private void validateConfiguration() {
        if (websocketPath == null || websocketPath.isBlank()) {
            throw new IllegalStateException("WebSocket server requires a non-blank websocketPath");
        }
        Objects.requireNonNull(messageCodec, "WebSocket server requires messageCodec");
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
