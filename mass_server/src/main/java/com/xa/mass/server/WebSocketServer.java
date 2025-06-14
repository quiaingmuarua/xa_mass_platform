package com.xa.mass.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

@Component
public class WebSocketServer {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketServer.class);
    private static final int PORT = 8080;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    @Autowired
    private WebSocketMessageHandler webSocketMessageHandler;

    @PostConstruct
    public void start() {
        bossGroup = new NioEventLoopGroup();
        workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(webSocketMessageHandler);

            // 保持主线程阻塞，直到服务器关闭
            b.bind(PORT).sync().channel().closeFuture().sync();
            logger.info("WebSocket server started on port {}", PORT);
        } catch (InterruptedException e) {
            logger.error("WebSocket server interrupted", e);
        }
    }

    @PreDestroy
    public void stop() {
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        logger.info("WebSocket server stopped");
    }
}