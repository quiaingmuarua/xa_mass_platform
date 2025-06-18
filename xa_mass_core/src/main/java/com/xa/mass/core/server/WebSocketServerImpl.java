package com.xa.mass.core.server;

import com.xa.mass.core.session.ServerSessionManager;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class WebSocketServerImpl implements MassWebSocketServer {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketServerImpl.class);

    @Value("${websocket.server.port:8088}")
    private int port;

    @Value("${websocket.server.path:/ws}")
    private String websocketPath;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    private volatile boolean running = false;

    private final AtomicLong activeConnections = new AtomicLong(0);

    @Autowired
    private ServerMessageHandler serverMessageHandler;

    @Autowired
    private ServerSessionManager sessionManager;

    @PostConstruct
    public void start() {
        start(this.port);
    }

    @Override
    public void start(int port) {
        this.port = port;
        this.running = true;

        boolean useEpoll = Epoll.isAvailable();
        bossGroup = useEpoll
                ? new EpollEventLoopGroup(1, new DefaultThreadFactory("boss"))
                : new NioEventLoopGroup(1, new DefaultThreadFactory("boss"));
        workerGroup = useEpoll
                ? new EpollEventLoopGroup(0, new DefaultThreadFactory("worker"))
                : new NioEventLoopGroup(0, new DefaultThreadFactory("worker"));

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(useEpoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.SO_RCVBUF, 512 * 1024)
                    .childOption(ChannelOption.SO_SNDBUF, 512 * 1024);

            if (useEpoll) {
                b.option(EpollChannelOption.SO_REUSEPORT, true);
            }

            b.childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ChannelPipeline pipeline = ch.pipeline();
                    pipeline.addLast(new HttpServerCodec());
                    pipeline.addLast(new HttpObjectAggregator(65536));
                    pipeline.addLast(new WebSocketServerProtocolHandler(
                            websocketPath, null, true, 65536 * 10, false, true, 10000L));
                    pipeline.addLast(new ConnectionStatsHandler());
                    pipeline.addLast(serverMessageHandler);
                }
            });

            ChannelFuture future = b.bind(port).sync();
            if (future.isSuccess()) {
                logger.info("✅ WebSocket server started on port {} with path {}", port, websocketPath);
            } else {
                logger.error("❌ Failed to start WebSocket server on port {}", port, future.cause());
                stop();
            }
        } catch (InterruptedException e) {
            logger.error("Server startup interrupted", e);
            Thread.currentThread().interrupt();
            stop();
        } catch (Exception e) {
            logger.error("Unexpected error during server startup", e);
            stop();
        }
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
            super.channelInactive(ctx);
        }
    }

    @PreDestroy
    @Override
    public void stop() {
        running = false;
        logger.info("🔻 Shutting down WebSocket server...");
        if (bossGroup != null) bossGroup.shutdownGracefully();
        if (workerGroup != null) workerGroup.shutdownGracefully();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public Channel getClientChannel(String clientId) {
        return sessionManager.getChannel(clientId, "messaegs_task");
    }

    public long getActiveConnectionCount() {
        return activeConnections.get();
    }
}
