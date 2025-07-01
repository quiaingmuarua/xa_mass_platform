package com.xa.mass.mock.starter;

import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.mock.client.ClientSessionManager;
import com.xa.mass.mock.client.MassWebSocketClientImpl;
import com.xa.mass.mock.config.MockConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import com.xa.mass.engine.monkey.MonkeyDeviceGenerator;
import com.xa.mass.eventbus.model.Device;
import com.xa.mass.eventbus.model.Token;
import java.nio.charset.StandardCharsets;

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
        String deviceConfigPath = "mock/mock_devices.json";
        String baseUri = mockConfig.getClient().getUri();
        // 读取并解析mock_devices.json
        List<Token> tokenList = new ArrayList<>();
        List<Device> devices;
        try (var is = getClass().getClassLoader().getResourceAsStream(deviceConfigPath)) {
            if (is == null) {
                log.warn("未找到mock设备配置文件: {}，不启动任何client", deviceConfigPath);
                return;
            }
            String deviceJson = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            devices = MonkeyDeviceGenerator.generateDevices(deviceJson, tokenList);
        }
        if (devices == null || devices.isEmpty()) {
            log.info("No devices found in mock_devices.json.");
            return;
        }
        clientExecutor = Executors.newFixedThreadPool(Math.min(devices.size(), 20));
        CountDownLatch latch = new CountDownLatch(devices.size());
        for (Device device : devices) {
            String deviceId = device.getDeviceId();
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
        latch.await(devices.size() * 15L, TimeUnit.SECONDS);
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
