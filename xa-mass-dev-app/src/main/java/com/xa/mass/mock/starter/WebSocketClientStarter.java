package com.xa.mass.mock.starter;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xa.mass.base.model.Worker;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.gateway.session.SessionRoles;
import com.xa.mass.mock.client.ClientSessionManager;
import com.xa.mass.mock.client.MassWebSocketClient;
import com.xa.mass.mock.client.MassWebSocketClientImpl;
import com.xa.mass.mock.command.mock.MockClientStateRegistry;
import com.xa.mass.mock.command.runtime.MockCommandRuntime;
import com.xa.mass.mock.config.MockConfig;
import com.xa.mass.sdk.MassSdkApplication;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
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

    @Autowired(required = false)
    private MockClientStateRegistry mockClientStateRegistry;

    // Keep a runtime dependency so Spring destroys mock clients before the embedded runtime.
    @Autowired(required = false)
    @SuppressWarnings("unused")
    private MassSdkApplication runtimeApplication;

    @Value("${mock.client.workers-config:mock/mock_workers.json}")
    private String workersConfigPath;

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

    @Value("${mock.client.task-result-status:SUCCESS}")
    private String taskResultStatus;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private ExecutorService clientExecutor;
    private ScheduledExecutorService pingScheduler;
    private volatile boolean isShuttingDown = false;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        startClients();
    }

    void startClients() {
        MDC.clear();
        if (!started.compareAndSet(false, true)) {
            log.info("Mock WebSocket clients already started, skipping duplicate startup");
            return;
        }

        log.info("Starting mock WebSocket clients");

        try {
            MockCommandRuntime.registerService(ClientSessionManager.class, clientSessionManager);
            if (mockClientStateRegistry != null) {
                MockCommandRuntime.registerService(MockClientStateRegistry.class, mockClientStateRegistry);
            }

            String baseUri = mockConfig.getClient().getUri();
            log.info("Target server: {}", baseUri);
            log.info("Worker config: {}", workersConfigPath);

            List<Worker> workers = loadWorkers();
            if (workers == null || workers.isEmpty()) {
                log.warn("No mock workers found, skipping client startup");
                started.set(false);
                return;
            }

            log.info("Found {} mock workers, establishing connections", workers.size());

            clientExecutor = Executors.newFixedThreadPool(Math.min(workers.size(), maxPoolSize));
            establishConnections(workers, baseUri);
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
     * Loads worker definitions from the configured classpath resource.
     */
    protected List<Worker> loadWorkers() {
        try (var is = getClass().getClassLoader().getResourceAsStream(workersConfigPath)) {
            if (is == null) {
                log.error("Worker config was not found: {}", workersConfigPath);
                return null;
            }
            ObjectMapper mapper = new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .registerModule(new JavaTimeModule());
            Worker[] workers = mapper.readValue(is, Worker[].class);
            if (workers == null || workers.length == 0) {
                return List.of();
            }
            List<Worker> result = new ArrayList<>(workers.length);
            for (Worker w : workers) result.add(w);
            return result;
        } catch (Exception e) {
            log.error("Failed to load worker config", e);
            return null;
        }
    }

    /**
     * Establishes worker connections against the gateway endpoint.
     */
    protected void establishConnections(List<Worker> workers, String baseUri) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(workers.size());
        List<Future<?>> futures = new ArrayList<>();

        for (Worker worker : workers) {
            String workerId = worker.getWorkerId();
            Future<?> future = clientExecutor.submit(() -> {
                try {
                    connectWorkerWithRetry(workerId, baseUri);
                } catch (Exception e) {
                    log.error("Worker {} failed to connect", workerId, e);
                } finally {
                    latch.countDown();
                }
            });
            futures.add(future);
        }

        boolean completed = latch.await(workers.size() * (connectionTimeout + 5L), TimeUnit.SECONDS);
        if (!completed) {
            log.warn("Some mock worker connections timed out");
        }

        int successCount = clientSessionManager.getClientCount();
        int failCount = workers.size() - successCount;
        log.info("Connection summary: success={}, failed={}", successCount, failCount);
    }

    /**
     * Connects a single mock worker with retry.
     */
    private void connectWorkerWithRetry(String workerId, String baseUri) {
        for (int attempt = 1; attempt <= retryAttempts; attempt++) {
            try {
                URI uri = new URI(baseUri);
                MassWebSocketClientImpl client = new MassWebSocketClientImpl(uri, workerId, taskResultStatus);
                clientSessionManager.addClient(client);

                if (client.connectBlocking(connectionTimeout, TimeUnit.SECONDS)) {
                    log.info("Worker {} connected successfully ({}/{})", workerId, attempt, retryAttempts);
                    return;
                }

                log.warn("Worker {} connection timed out ({}/{})", workerId, attempt, retryAttempts);
            } catch (Exception e) {
                log.warn("Worker {} connection failed ({}/{}): {}", workerId, attempt, retryAttempts, e.getMessage());
            }

            clientSessionManager.removeClient(workerId);

            if (attempt < retryAttempts) {
                try {
                    Thread.sleep(retryDelay * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        log.error("Worker {} failed after {} retries", workerId, retryAttempts);
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

        Collection<MassWebSocketClient> clients = clientSessionManager.getAllClients();
        if (clients.isEmpty()) {
            log.debug("No active mock client connections");
            return;
        }

        List<MassWebSocketClient> clientList = new ArrayList<>(clients);
        MassWebSocketClient client = clientList.get(new Random().nextInt(clientList.size()));

        try {
            MassMessage ping = new MassMessage();
            ping.setMsgId("ping-" + client.getWorkerId() + "-" + System.currentTimeMillis());
            ping.setMsgType(MessageType.PING);
            ping.setFrom(MessageDirection.CLIENT);
            ping.setSubMsgType("heartbeat");

            MessageContext ctx = new MessageContext();
            ctx.setWorkerId(client.getWorkerId());
            ctx.setConnRole(SessionRoles.TASK_MESSAGES);
            ping.setContext(ctx);

            client.sendMessage(new com.google.gson.Gson().toJson(ping));
            log.debug("[{}] heartbeat sent", client.getWorkerId());
        } catch (Exception e) {
            log.warn("[{}] heartbeat failed: {}", client.getWorkerId(), e.getMessage());
            clientSessionManager.removeClient(client.getWorkerId());
        }
    }

    public String getConnectionStats() {
        int totalClients = clientSessionManager.getClientCount();
        return String.format("Active connections: %d", totalClients);
    }

    @PreDestroy
    public void shutdown() {
        MDC.clear();
        log.info("Shutting down mock WebSocket clients");
        isShuttingDown = true;
        started.set(false);

        Collection<MassWebSocketClient> clients = clientSessionManager.getAllClients();
        log.info("Disconnecting {} mock clients", clients.size());

        for (MassWebSocketClient client : clients) {
            try {
                client.disconnect();
                log.debug("Client {} disconnected", client.getWorkerId());
            } catch (Exception e) {
                log.warn("Failed to disconnect client {}", client.getWorkerId(), e);
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
        MDC.clear();
    }
}
