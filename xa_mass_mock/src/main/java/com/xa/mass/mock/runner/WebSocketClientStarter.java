package com.xa.mass.mock.runner;

import com.xa.mass.core.client.MassWebSocketClientImpl;
import com.xa.mass.core.model.message.MassMessage;
import com.xa.mass.core.model.message.MessageContext;
import com.xa.mass.core.model.message.enums.MessageDirection;
import com.xa.mass.core.model.message.enums.MessageType;
import com.xa.mass.core.session.ClientSessionManager;
import com.xa.mass.mock.config.MockConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;

@Component
@Profile("client")
public class WebSocketClientStarter implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(WebSocketClientStarter.class);

    @Autowired
    private MockConfig mockConfig;

    @Autowired
    private ClientSessionManager clientSessionManager;

    private ExecutorService clientExecutor;
    private ScheduledExecutorService pingScheduler;

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
            MassWebSocketClientImpl client = new MassWebSocketClientImpl(uri, deviceId);
            clientSessionManager.addClient(client);

            clientExecutor.submit(() -> {
                try {
                    if (client.connectBlocking(10, TimeUnit.SECONDS)) {
                        log.info("✅ Client {} connected", deviceId);
                    } else {
                        log.warn("⚠️ Client {} connect timeout", deviceId);
                        clientSessionManager.removeClient(deviceId);
                    }
                } catch (Exception e) {
                    log.error("❌ Client {} connection failed", deviceId, e);
                    clientSessionManager.removeClient(deviceId);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(numberOfClients * 15L, TimeUnit.SECONDS);
        log.info("✅ All connection attempts completed. Active: {}", clientSessionManager.getClientCount());

        startPingTask();
    }

    private void startPingTask() {
        if (pingScheduler == null) {
            pingScheduler = Executors.newSingleThreadScheduledExecutor();
            pingScheduler.scheduleAtFixedRate(this::sendRandomPing, 5, 10, TimeUnit.SECONDS);
        }
    }

    private void sendRandomPing() {
        Collection<MassWebSocketClientImpl> clients = clientSessionManager.getAllClients();
        if (clients.isEmpty()) return;
        List<MassWebSocketClientImpl> clientList = new ArrayList<>(clients);
        MassWebSocketClientImpl client = clientList.get(new Random().nextInt(clientList.size()));
        try {
            MassMessage ping = new MassMessage();
            ping.setMsgId("ping-" + client.getDeviceId() + "-" + System.currentTimeMillis());
            ping.setMsgType(MessageType.PING);
            ping.setFrom(MessageDirection.CLIENT);
            ping.setSubMsgType("heartbeat");
            MessageContext ctx = new MessageContext();
            ctx.setDeviceId(client.getDeviceId());
            ctx.setConnRole("messages_task");
            ping.setContext(ctx);
            client.send(new com.google.gson.Gson().toJson(ping));
            log.info("[{}] 定时发送 PING 消息", client.getDeviceId());
        } catch (Exception e) {
            log.warn("[{}] 定时发送 PING 失败: {}", client.getDeviceId(), e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("🛑 Shutting down clients...");
        for (MassWebSocketClientImpl client : clientSessionManager.getAllClients()) {
            try {
                client.disconnect();
            } catch (Exception e) {
                log.warn("Error disconnecting client", e);
            }
        }
        if (clientExecutor != null) {
            clientExecutor.shutdown();
        }
        if (pingScheduler != null) {
            pingScheduler.shutdown();
        }
    }
}
