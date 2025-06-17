package com.xa.mass.core.server;

import com.xa.mass.core.manager.ServerSessionManager;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Component
public class WebSocketServerImpl implements MassWebSocketServer {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketServerImpl.class);

    // 考虑将端口号配置application.properties
    @Value("${websocket.server.port:8088}") // 默认8088
    private int port;

    @Value("${websocket.server.path:/ws}") // WebSocket 路径
    private String websocketPath;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    @Autowired
    private ServerMessageHandler serverMessageHandler; // Spring 会注入这Sharable Handler

    @Autowired
    private ServerSessionManager sessionManager;

    private volatile boolean running = false;

    @PostConstruct
    public void start() {
        start(this.port);
    }

    @Override
    public void start(int port) {
        this.port = port;
        running = true;
        bossGroup = new NioEventLoopGroup(1); // 通常 bossGroup 只需要一个线
        workerGroup = new NioEventLoopGroup(); // workerGroup 可以根据 CPU 核数配置

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.INFO)) // 可选：Netty自身的日志，用于调试连接建立
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new HttpServerCodec()); // HTTP 编解码器
                            pipeline.addLast(new HttpObjectAggregator(65536)); // HTTP 消息的多个部分聚合为单个 FullHttpRequest FullHttpResponse
                            // WebSocketServerProtocolHandler 处理 WebSocket 握手、Ping/Pong 帧等
                            pipeline.addLast(new WebSocketServerProtocolHandler(websocketPath, null, true, 65536 * 10, false, true, 10000L));
                            pipeline.addLast(serverMessageHandler); // 自定义的业务逻辑处理
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128) // 设置TCP连接的等待队列长
                    .childOption(ChannelOption.SO_KEEPALIVE, true); // 开启TCP KeepAlive

            // 绑定端口并启动服务器，sync() 会等待绑定完
            ChannelFuture future = b.bind(port).sync();

            if (future.isSuccess()) {
                logger.info("WebSocket server started successfully on port {} with path {}", port, websocketPath);
            } else {
                logger.error("Failed to start WebSocket server on port {}", port, future.cause());
                // 如果启动失败，确保资源被释放
                stop();
            }

            // 服务器启动后，不需要在这里阻塞主线(future.channel().closeFuture().sync();)
            // EventLoopGroup 会保持服务器运行，@PreDestroy 会处理关
        } catch (InterruptedException e) {
            logger.error("WebSocket server start interrupted", e);
            Thread.currentThread().interrupt(); // 恢复中断状
            stop(); // 发生中断时也尝试关闭资源
        } catch (Exception e) {
            logger.error("Failed to start WebSocket server due to an unexpected error", e);
            stop(); // 发生其他异常时也尝试关闭资源
        }
    }

    @Override
    public void stop() {
        running = false;
        logger.info("Attempting to stop WebSocket server...");
        if (bossGroup != null && !bossGroup.isShuttingDown() && !bossGroup.isShutdown()) {
            try {
                bossGroup.shutdownGracefully().sync(); // 等待 bossGroup 关闭完成
            } catch (InterruptedException e) {
                logger.error("Interrupted while shutting down bossGroup", e);
                Thread.currentThread().interrupt();
            }
        }
        if (workerGroup != null && !workerGroup.isShuttingDown() && !workerGroup.isShutdown()) {
            try {
                workerGroup.shutdownGracefully().sync(); // 等待 workerGroup 关闭完成
            } catch (InterruptedException e) {
                logger.error("Interrupted while shutting down workerGroup", e);
                Thread.currentThread().interrupt();
            }
        }
        logger.info("WebSocket server stopped.");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public Channel getClientChannel(String clientId) {
        // 只取默认 role
        return sessionManager.getChannel(clientId, "messaegs_task");
    }
}
