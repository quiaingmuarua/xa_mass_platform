package com.xa.mass.mock;

import com.xa.mass.core.client.MassWebSocketClient;
import com.xa.mass.core.client.MassWebSocketClientImpl;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.net.URI;

@SpringBootApplication(scanBasePackages = {"com.xa.mass.core", "com.xa.mass.mock"})
public class WebSocketClientSpringBootApp implements CommandLineRunner {

    @Override
    public void run(String... args) throws Exception {
        String deviceId = "test-device-002";
        MassWebSocketClient client = new MassWebSocketClientImpl(new URI("ws://localhost:18088/ws"), deviceId);
        client.connect(new URI("ws://localhost:18088/ws"));
        System.out.println("WebSocket client connected as " + deviceId);
        Thread.currentThread().join();
    }

    public static void main(String[] args) {
        SpringApplication.run(WebSocketClientSpringBootApp.class, args);
    }







} 