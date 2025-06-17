package com.xa.mass.mock;

import com.xa.mass.core.client.MassWebSocketClientImpl;
import com.xa.mass.core.server.MassWebSocketServer;
import com.xa.mass.core.server.WebSocketServerImpl;
import com.xa.mass.mock.config.MockConfig;
import com.xa.mass.core.client.service.ClientSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
@Slf4j
@SpringBootApplication
@EnableScheduling
@RequiredArgsConstructor
@ComponentScan(basePackages = {"com.xa.mass.mock", "com.xa.mass.core"}) // 添加需要扫描的包
public class MassMockApplication {
    private final MockConfig mockConfig;
    private final ClientSessionManager clientSessionManager;
    private MassWebSocketServer webSocketServer;

    private static final Logger logger = LoggerFactory.getLogger(MassMockApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(MassMockApplication.class, args);
    }

    @Bean
    public CommandLineRunner commandLineRunner() {
        return args -> {
            log.info("🚀 Starting Mass Mock Application...");
            createMockClients(mockConfig.getClient().getCount());
            try {
                logger.info("Starting WebSocket server via CommandLineRunner...");
                webSocketServer.start(8088); // This will no longer block
                logger.info("WebSocket server start initiated.");
            } catch (Exception e) {
                logger.error("Failed to start WebSocket server", e);
            }
        };
    }

    @Bean
    public MassWebSocketServer massWebSocketServer(WebSocketServerImpl webSocketServerImpl) {
        return webSocketServerImpl;
    }

    public void createMockClients(int count) {
        CountDownLatch connectLatch = new CountDownLatch(count);

        for (int i = 0; i < count; i++) {
            String deviceId = "mock_device_" + String.format("%03d", i + 1);
            MassWebSocketClientImpl client = new MassWebSocketClientImpl(java.net.URI.create(mockConfig.getClient().getUri()), deviceId);
            clientSessionManager.addClient(client);

            new Thread(() -> {
                try {
                    log.info("🚀 Attempting to connect client: {}", deviceId);
                    if (client.connectBlocking(5, TimeUnit.SECONDS)) {
                        log.info("Client {} connected successfully.", deviceId);
                    } else {
                        log.warn("⚠️ Client {} failed to connect within timeout.", deviceId);
                        clientSessionManager.removeClient(deviceId);
                    }
                } catch (Exception e) {
                    log.error("🔌 Client {} connection failed: {}", deviceId, e.getMessage());
                    clientSessionManager.removeClient(deviceId);
                } finally {
                    connectLatch.countDown();
                }
            }, "client-starter-" + deviceId).start();
        }

        try {
            if (!connectLatch.await(count * 7L, TimeUnit.SECONDS)) {
                log.warn("⚠️ Not all clients completed their initial connection attempt within timeout.");
            }
        } catch (InterruptedException e) {
            log.error("Interrupted while waiting for clients to connect.", e);
            Thread.currentThread().interrupt();
        }
    }

    @Scheduled(fixedRate = 30000)  // 直接使用 fixedRate，值以毫秒为单
    public void createAndSendMockTask() {
        clientSessionManager.sendMockTask();
    }

    // 添加JVM关闭钩子
    @Bean
    public void shutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("🔌 Shutting down all mock clients...");
            clientSessionManager.getAllClients().forEach(MassWebSocketClientImpl::closeConnection);
            log.info("🔌 All mock clients have been requested to close.");
        }));
    }
}
