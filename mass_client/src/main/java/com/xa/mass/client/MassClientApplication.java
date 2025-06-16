package com.xa.mass.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
public class MassClientApplication {
    private static final Logger logger = LoggerFactory.getLogger(MassClientApplication.class);
    private static final int NUMBER_OF_CLIENTS = 5; // 你想要启动的客户端数量
    private static final String SERVER_URI = "ws://localhost:8088/ws"; // WebSocket 服务器地址

    public static void main(String[] args) {
        SpringApplication.run(MassClientApplication.class, args);
    }

    @Bean
    public CommandLineRunner commandLineRunner() {
        return args -> {
            logger.info("🚀 Starting {} WebSocket clients...", NUMBER_OF_CLIENTS);
            List<TaskWebSocketClient> clients = new ArrayList<>();
            CountDownLatch connectLatch = new CountDownLatch(NUMBER_OF_CLIENTS);

            for (int i = 0; i < NUMBER_OF_CLIENTS; i++) {
                String deviceId = "mock_device_" + String.format("%03d", i + 1);
                TaskWebSocketClient client = new TaskWebSocketClient(java.net.URI.create(SERVER_URI), deviceId);
                clients.add(client);

                // 在新线程中连接，避免阻塞Spring Boot启动
                new Thread(() -> {
                    try {
                        logger.info("🚀 Attempting to connect client: {}", client.getDeviceId());
                        // client.connectBlocking(); // connectBlocking 会阻塞当前线程直到连接成功或失败
                        if (client.connectBlocking(5, TimeUnit.SECONDS)) { // 带超时的阻塞连接
                            logger.info("✅ Client {} connected successfully.", client.getDeviceId());
                        } else {
                            logger.warn("⚠️ Client {} failed to connect within timeout.", client.getDeviceId());
                            // onClose 会被调用，触发重连逻辑
                        }
                    } catch (InterruptedException e) {
                        logger.error("🔌 Client {} connection attempt interrupted.", client.getDeviceId(), e);
                        Thread.currentThread().interrupt();
                        client.closeConnection(); // 清理
                    } catch (Exception e) {
                        logger.error("🔌 Client {} initial connection failed: {}", client.getDeviceId(), e.getMessage());
                        // onClose 应该会被调用，从而触发重连逻辑
                    } finally {
                        connectLatch.countDown();
                    }
                }, "client-starter-" + deviceId).start();
            }

            // 等待所有客户端尝试连接（可选，主要用于观察初始连接状态）
            try {
                if (!connectLatch.await(NUMBER_OF_CLIENTS * 7L, TimeUnit.SECONDS)) { // 给予足够时间
                    logger.warn("⚠️ Not all clients may have completed their initial connection attempt within the timeout.");
                }
            } catch (InterruptedException e) {
                logger.error("Interrupted while waiting for clients to connect.", e);
                Thread.currentThread().interrupt();
            }


            logger.info("All {} client connection attempts initiated.", NUMBER_OF_CLIENTS);

            // 添加JVM关闭钩子，确保所有客户端在程序退出时正确关闭
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("🔌 Shutting down all clients...");
                for (TaskWebSocketClient client : clients) {
                    client.closeConnection();
                }
                logger.info("🔌 All clients have been requested to close.");
            }));

            // Spring Boot 应用会保持运行，所以不需要额外的 Thread.sleep(Long.MAX_VALUE)
        };
    }
}