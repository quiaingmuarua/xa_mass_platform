package com.xa.mass.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Component
public class WebSocketServer {

    @PostConstruct
    public void start() {
        EventLoopGroup boss = new NioEventLoopGroup();
        EventLoopGroup worker = new NioEventLoopGroup();

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(boss, worker)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new WebSocketMessageHandler());

            b.bind(8080).sync();
            System.out.println("WebSocket server started on port 8080");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}