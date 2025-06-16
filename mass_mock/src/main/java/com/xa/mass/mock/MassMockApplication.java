package com.xa.mass.mock;

import com.xa.mass.mock.client.TaskWebSocketClient;
import com.xa.mass.mock.config.MockConfig;
import com.xa.mass.mock.service.MockTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Slf4j
@SpringBootApplication
@EnableScheduling
@RequiredArgsConstructor
public class MassMockApplication {
    private final MockConfig mockConfig;
    private final MockTaskService mockTaskService;

    public static void main(String[] args) {
        SpringApplication.run(MassMockApplication.class, args);
    }

    @Bean
    public CommandLineRunner commandLineRunner() {
        return args -> {
            log.info("🚀 Starting Mass Mock Application...");
            createMockClients(mockConfig.getClient().getCount());
        };
    }

    public void createMockClients(int count) {
        CountDownLatch connectLatch = new CountDownLatch(count);

        for (int i = 0; i < count; i++) {
            String deviceId = "mock_device_" + String.format("%03d", i + 1);
            TaskWebSocketClient client = new TaskWebSocketClient(java.net.URI.create(mockConfig.getClient().getUri()), deviceId);
            mockTaskService.addClient(client);

            new Thread(() -> {
                try {
                    log.info("🚀 Attempting to connect client: {}", deviceId);
                    if (client.connectBlocking(5, TimeUnit.SECONDS)) {
                        log.info("✅ Client {} connected successfully.", deviceId);
                    } else {
                        log.warn("⚠️ Client {} failed to connect within timeout.", deviceId);
                        mockTaskService.removeClient(deviceId);
                    }
                } catch (Exception e) {
                    log.error("🔌 Client {} connection failed: {}", deviceId, e.getMessage());
                    mockTaskService.removeClient(deviceId);
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

    @Scheduled(fixedRate = 30000)  // 直接使用 fixedRate，值以毫秒为单位
    public void createAndSendMockTask() {
       mockTaskService.sendMockTask();
    }

    // 添加JVM关闭钩子
    @Bean
    public void shutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("🔌 Shutting down all mock clients...");
            mockTaskService.getAllClients().forEach(TaskWebSocketClient::closeConnection);
            log.info("🔌 All mock clients have been requested to close.");
        }));
    }
}