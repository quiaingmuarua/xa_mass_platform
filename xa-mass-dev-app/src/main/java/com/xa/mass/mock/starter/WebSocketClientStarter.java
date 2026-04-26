package com.xa.mass.mock.starter;

import com.xa.mass.base.model.Worker;
import com.xa.mass.mock.client.ClientSessionManager;
import com.xa.mass.mock.client.MockWorkerClient;
import com.xa.mass.mock.client.MockWorkerWebSocketClient;
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
import java.util.concurrent.*;
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

    @Autowired(required = false)
    private MassSdkApplication runtimeApplication;

    @Value("${mock.client.connection-timeout:10}")
    private int connectionTimeout;

    @Value("${mock.client.max-pool-size:20}")
    private int maxPoolSize;

    @Value("${mock.client.retry-attempts:3}")
    private int retryAttempts;

    @Value("${mock.client.retry-delay:5}")
    private int retryDelay;

    @Value("${mock.client.task-result-status:SUCCESS}")
    private String taskResultStatus;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private ExecutorService clientExecutor;
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

            List<Worker> workers = loadWorkers();
            if (workers == null || workers.isEmpty()) {
                log.warn("No SDK-registered websocket mock workers found, skipping client startup");
                started.set(false);
                return;
            }

            log.info("Found {} SDK-registered websocket mock workers, establishing connections", workers.size());

            clientExecutor = Executors.newFixedThreadPool(Math.min(workers.size(), maxPoolSize));
            establishConnections(workers, baseUri);

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
     * Discovers dev mock clients from SDK-registered worker resources.
     *
     * <p>The starter intentionally does not read worker JSON. Resource creation
     * belongs to the SDK runtime; this class only opens WebSocket adapter clients
     * for workers whose concrete transport identity is the WebSocket adapter.
     */
    protected List<Worker> loadWorkers() {
        if (runtimeApplication == null) {
            log.warn("MassSdkApplication is not available; cannot discover mock clients");
            return List.of();
        }
        return runtimeApplication.getAllWorkers().stream()
                .filter(this::isWebSocketClientWorker)
                .toList();
    }

    protected boolean isWebSocketClientWorker(Worker worker) {
        if (worker == null || worker.getWorkerId() == null || worker.getWorkerId().isBlank()) {
            return false;
        }
        String adapterId = worker.getAdapterId();
        return adapterId != null && "websocket".equalsIgnoreCase(adapterId.trim());
    }

    /**
     * Establishes worker connections against the WebSocket transport endpoint.
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
                MockWorkerWebSocketClient client = new MockWorkerWebSocketClient(uri, workerId, taskResultStatus);
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

        Collection<MockWorkerClient> clients = clientSessionManager.getAllClients();
        log.info("Disconnecting {} mock clients", clients.size());

        for (MockWorkerClient client : clients) {
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

        log.info("Mock WebSocket clients stopped");
        MDC.clear();
    }
}
