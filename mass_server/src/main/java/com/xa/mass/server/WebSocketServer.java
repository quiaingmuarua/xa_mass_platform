package com.xa.mass.server;

import com.xa.mass.server.handler.PingHandler;
import com.xa.mass.server.handler.WebSocketMessageHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Component
public class WebSocketServer {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketServer.class);
    private static final int PORT = 8088;

    @PostConstruct
    public void start() {
        EventLoopGroup boss = new NioEventLoopGroup();
        EventLoopGroup worker = new NioEventLoopGroup();

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(boss, worker)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new WebSocketMessageHandler());

            // 保持主线程阻塞，直到服务器关闭
            b.bind(PORT).sync().channel().closeFuture().sync();
            logger.info("WebSocket server started on port {}", PORT);
        } catch (InterruptedException e) {
            logger.error("WebSocket server interrupted", e);
        } finally {
            boss.shutdownGracefully();
            worker.shutdownGracefully();
        }
    }
}