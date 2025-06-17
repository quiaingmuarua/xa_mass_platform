package com.xa.mass.mock.runner;

import com.xa.mass.core.client.MassWebSocketClient;
import com.xa.mass.core.client.MassWebSocketClientImpl;
import com.xa.mass.mock.config.MockConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.boot.CommandLineRunner;

import javax.annotation.PreDestroy;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;

@Component
@Profile("client")
public class WebSocketClientStarter implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(WebSocketClientStarter.class);

    @Autowired
    private MockConfig mockConfig;

    private ExecutorService clientExecutor;
    private final List<MassWebSocketClient> activeClients = new CopyOnWriteArrayList<>();

    @Override
    public void run(String... args) throws Exception {
        int numberOfClients = mockConfig.getClient().getCount();
        String baseUri = mockConfig.getClient().getUri();

        if (numberOfClients <= 0) {
            log.info("No clients to start based on configuration.");
            return;
        }

        clientExecutor = Executors.newFixedThreadPool(Math.min(numberOfClients, 20));
        CountDownLatch latch = new CountDownLatch(numberOfClients);

        for (int i = 0; i < numberOfClients; i++) {
            String deviceId = "app_client_" + String.format("%03d", i + 1);
            URI uri = new URI(baseUri);
            MassWebSocketClient client = new MassWebSocketClientImpl(uri, deviceId);
            activeClients.add(client);

            clientExecutor.submit(() -> {
                try {
                    if (client.connectBlocking(10, TimeUnit.SECONDS)) {
                        log.info("✅ Client {} connected", deviceId);
                    } else {
                        log.warn("⚠️ Client {} connect timeout", deviceId);
                        activeClients.remove(client);
                    }
                } catch (Exception e) {
                    log.error("❌ Client {} connection failed", deviceId, e);
                    activeClients.remove(client);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(numberOfClients * 15L, TimeUnit.SECONDS);
        log.info("✅ All connection attempts completed. Active: {}", activeClients.size());
    }

    @PreDestroy
    public void shutdown() {
        log.info("🛑 Shutting down clients...");
        for (MassWebSocketClient client : activeClients) {
            try {
                client.disconnect();
            } catch (Exception e) {
                log.warn("Error disconnecting client", e);
            }
        }
        if (clientExecutor != null) {
            clientExecutor.shutdown();
        }
    }
}
