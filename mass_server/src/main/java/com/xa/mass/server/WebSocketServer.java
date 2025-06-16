package com.xa.mass.server;

import com.xa.mass.server.manager.WebSocketMessageHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
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
import javax.annotation.PreDestroy;

@Component
public class WebSocketServer {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketServer.class);

    // 考虑将端口号配置在 application.properties 中
    @Value("${websocket.server.port:8088}") // 默认为 8088
    private int port;

    @Value("${websocket.server.path:/ws}") // WebSocket 路径
    private String websocketPath;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    @Autowired
    private WebSocketMessageHandler webSocketMessageHandler; // Spring 会注入这个 Sharable Handler

    @PostConstruct
    public void start() {
        bossGroup = new NioEventLoopGroup(1); // 通常 bossGroup 只需要一个线程
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
                            pipeline.addLast(new HttpObjectAggregator(65536)); // 将 HTTP 消息的多个部分聚合为单个 FullHttpRequest 或 FullHttpResponse
                            // WebSocketServerProtocolHandler 处理 WebSocket 握手、Ping/Pong 帧等
                            pipeline.addLast(new WebSocketServerProtocolHandler(websocketPath, null, true, 65536 * 10, false, true, 10000L));
                            pipeline.addLast(webSocketMessageHandler); // 自定义的业务逻辑处理器
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128) // 设置TCP连接的等待队列长度
                    .childOption(ChannelOption.SO_KEEPALIVE, true); // 开启TCP KeepAlive

            // 绑定端口并启动服务器，sync() 会等待绑定完成
            ChannelFuture future = b.bind(port).sync();

            if (future.isSuccess()) {
                logger.info("WebSocket server started successfully on port {} with path {}", port, websocketPath);
            } else {
                logger.error("Failed to start WebSocket server on port {}", port, future.cause());
                // 如果启动失败，确保资源被释放
                stop();
            }

            // 服务器启动后，不需要在这里阻塞主线程 (future.channel().closeFuture().sync();)
            // EventLoopGroup 会保持服务器运行，@PreDestroy 会处理关闭
        } catch (InterruptedException e) {
            logger.error("WebSocket server start interrupted", e);
            Thread.currentThread().interrupt(); // 恢复中断状态
            stop(); // 发生中断时也尝试关闭资源
        } catch (Exception e) {
            logger.error("Failed to start WebSocket server due to an unexpected error", e);
            stop(); // 发生其他异常时也尝试关闭资源
        }
    }

    @PreDestroy
    public void stop() {
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
}