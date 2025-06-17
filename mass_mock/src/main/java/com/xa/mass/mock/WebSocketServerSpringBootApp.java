package com.xa.mass.mock;

import com.xa.mass.core.server.MassWebSocketServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.xa.mass.core", "com.xa.mass.mock"})
public class WebSocketServerSpringBootApp implements CommandLineRunner {

    @Autowired
    private MassWebSocketServer webSocketServer;

    public static void main(String[] args) {
        SpringApplication.run(WebSocketServerSpringBootApp.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        int port = 18088;
        webSocketServer.start(port);
        System.out.println("WebSocket server started on port " + port);
        // 挂载主线程
        Thread.currentThread().join();
    }
} 