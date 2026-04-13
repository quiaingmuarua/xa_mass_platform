package com.xa.mass.mock.starter;

import com.xa.mass.base.model.Device;
import com.xa.mass.engine.monkey.MonkeyGenerator;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.gateway.session.SessionRoles;
import com.xa.mass.mock.client.ClientSessionManager;
import com.xa.mass.mock.client.MassWebSocketClientImpl;
import com.xa.mass.mock.config.MockConfig;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starts mock WebSocket clients after the full dev stack is ready.
 */
@Component
@ConditionalOnProperty(prefix = "mock.client", name = "auto-start", havingValue = "true")
public class WebSocketClientStarter {

    private static final Logger log = LoggerFactory.getLogger(WebSocketClientStarter.class);

    @Autowired
    private MockConfig mockConfig;

    @Autowired
    private ClientSessionManager clientSessionManager;

    @Value("${mock.client.devices-config:mock/mock_devices.json}")
    private String devicesConfigPath;

    @Value("${mock.client.connection-timeout:10}")
    private int connectionTimeout;

    @Value("${mock.client.max-pool-size:20}")
    private int maxPoolSize;

    @Value("${mock.client.ping-interval:10}")
    private int pingInterval;

    @Value("${mock.client.ping-delay:5}")
    private int pingDelay;

    @Value("${mock.client.retry-attempts:3}")
    private int retryAttempts;

    @Value("${mock.client.retry-delay:5}")
    private int retryDelay;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private ExecutorService clientExecutor;
    private ScheduledExecutorService pingScheduler;
    private volatile boolean isShuttingDown = false;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        startClients();
    }

    void startClients() {
        if (!started.compareAndSet(false, true)) {
            log.info("Mock WebSocket clients already started, skipping duplicate startup");
            return;
        }

        log.info("Starting mock WebSocket clients");

        try {
            String baseUri = mockConfig.getClient().getUri();
            log.info("Target server: {}", baseUri);
            log.info("Device config: {}", devicesConfigPath);

            List<Device> devices = loadDevices();
            if (devices == null || devices.isEmpty()) {
                log.warn("No mock devices found, skipping client startup");
                started.set(false);
                return;
            }

            log.info("Found {} mock devices, establishing connections", devices.size());

            clientExecutor = Executors.newFixedThreadPool(Math.min(devices.size(), maxPoolSize));
            establishConnections(devices, baseUri);
            startPingTask();

            log.info("Mock WebSocket clients started, active connections: {}",
                    clientSessionManager.getClientCount());
        } catch (RuntimeException e) {
            started.set(false);
            throw e;
        } catch (Exception e) {
            started.set(false);
            throw new IllegalStateException("Failed to start mock WebSocket clients", e);
        }
    }

    /**
     * Loads device definitions from the configured classpath resource.
     */
    protected List<Device> loadDevices() {
        try (var is = getClass().getClassLoader().getResourceAsStream(devicesConfigPath)) {
            if (is == null) {
                log.error("Device config was not found: {}", devicesConfigPath);
                return null;
            }

            String deviceJson = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            com.google.gson.JsonElement elem = com.google.gson.JsonParser.parseString(deviceJson);
            List<Device> devices = new ArrayList<>();
            if (elem.isJsonArray()) {
                for (com.google.gson.JsonElement dsl : elem.getAsJsonArray()) {
                    devices.addAll(MonkeyGenerator.generateDevices(dsl.toString()));
                }
            } else {
                devices.addAll(MonkeyGenerator.generateDevices(deviceJson));
            }
            return devices;
        } catch (Exception e) {
            log.error("Failed to load mock devices", e);
            return null;
        }
    }

    /**
     * Establishes device connections against the gateway endpoint.
     */
    protected void establishConnections(List<Device> devices, String baseUri) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(devices.size());
        List<Future<?>> futures = new ArrayList<>();

        for (Device device : devices) {
            String deviceId = device.getDeviceId();
            Future<?> future = clientExecutor.submit(() -> {
                try {
                    connectDeviceWithRetry(deviceId, baseUri);
                } catch (Exception e) {
                    log.error("Device {} failed to connect", deviceId, e);
                } finally {
                    latch.countDown();
                }
            });
            futures.add(future);
        }

        boolean completed = latch.await(devices.size() * (connectionTimeout + 5L), TimeUnit.SECONDS);
        if (!completed) {
            log.warn("Some mock device connections timed out");
        }

        int successCount = clientSessionManager.getClientCount();
        int failCount = devices.size() - successCount;
        log.info("Connection summary: success={}, failed={}", successCount, failCount);
    }

    /**
     * Connects a single mock device with retry.
     */
    private void connectDeviceWithRetry(String deviceId, String baseUri) {
        for (int attempt = 1; attempt <= retryAttempts; attempt++) {
            try {
                URI uri = new URI(baseUri);
                MassWebSocketClientImpl client = new MassWebSocketClientImpl(uri, deviceId);
                clientSessionManager.addClient(client);

                if (client.connectBlocking(connectionTimeout, TimeUnit.SECONDS)) {
                    log.info("Device {} connected successfully ({}/{})", deviceId, attempt, retryAttempts);
                    return;
                }

                log.warn("Device {} connection timed out ({}/{})", deviceId, attempt, retryAttempts);
            } catch (Exception e) {
                log.warn("Device {} connection failed ({}/{}): {}", deviceId, attempt, retryAttempts, e.getMessage());
            }

            clientSessionManager.removeClient(deviceId);

            if (attempt < retryAttempts) {
                try {
                    Thread.sleep(retryDelay * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        log.error("Device {} failed after {} retries", deviceId, retryAttempts);
    }

    /**
     * Starts the periodic client heartbeat.
     */
    protected void startPingTask() {
        if (pingScheduler == null) {
            pingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "client-ping-scheduler");
                t.setDaemon(true);
                return t;
            });

            pingScheduler.scheduleAtFixedRate(this::sendRandomPing, pingDelay, pingInterval, TimeUnit.SECONDS);
            log.info("Mock client heartbeat started, interval={}s", pingInterval);
        }
    }

    /**
     * Sends a ping from a random connected client to keep sessions warm.
     */
    private void sendRandomPing() {
        if (isShuttingDown) {
            return;
        }

        Collection<MassWebSocketClientImpl> clients = clientSessionManager.getAllClients();
        if (clients.isEmpty()) {
            log.debug("No active mock client connections");
            return;
        }

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
            ctx.setConnRole(SessionRoles.TASK_MESSAGES);
            ping.setContext(ctx);

            client.send(new com.google.gson.Gson().toJson(ping));
            log.debug("[{}] heartbeat sent", client.getDeviceId());
        } catch (Exception e) {
            log.warn("[{}] heartbeat failed: {}", client.getDeviceId(), e.getMessage());
            clientSessionManager.removeClient(client.getDeviceId());
        }
    }

    public String getConnectionStats() {
        int totalClients = clientSessionManager.getClientCount();
        return String.format("Active connections: %d", totalClients);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down mock WebSocket clients");
        isShuttingDown = true;

        Collection<MassWebSocketClientImpl> clients = clientSessionManager.getAllClients();
        log.info("Disconnecting {} mock clients", clients.size());

        for (MassWebSocketClientImpl client : clients) {
            try {
                client.disconnect();
                log.debug("Client {} disconnected", client.getDeviceId());
            } catch (Exception e) {
                log.warn("Failed to disconnect client {}", client.getDeviceId(), e);
            }
        }

        if (clientExecutor != null) {
            clientExecutor.shutdown();
            try {
                if (!clientExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    clientExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                clientExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (pingScheduler != null) {
            pingScheduler.shutdown();
            try {
                if (!pingScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    pingScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                pingScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log.info("Mock WebSocket clients stopped");
    }
}
