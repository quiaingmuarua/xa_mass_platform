package com.xa.mass.workerpack.sample.starter;

import com.xa.mass.base.model.Worker;
import com.xa.mass.workerpack.sample.client.ClientSessionManager;
import com.xa.mass.workerpack.sample.client.SampleWorkerClient;
import com.xa.mass.workerpack.sample.command.fixture.SampleClientStateRegistry;
import com.xa.mass.workerpack.sample.command.runtime.SampleCommandRuntime;
import com.xa.mass.sdk.MassSdkApplication;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared startup orchestration for adapter-specific dev-app mock worker clients.
 */
public abstract class AbstractSampleWorkerClientStarter {

    @Autowired
    protected ClientSessionManager clientSessionManager;

    @Autowired(required = false)
    protected SampleClientStateRegistry SampleClientStateRegistry;

    @Autowired(required = false)
    protected MassSdkApplication runtimeApplication;

    protected int connectionTimeout;
    protected int maxPoolSize;
    protected int retryAttempts;
    protected int retryDelay;
    protected String taskResultStatus;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private ExecutorService clientExecutor;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        startClients();
    }

    void startClients() {
        Logger log = logger();
        MDC.clear();
        if (!started.compareAndSet(false, true)) {
            log.info("Mock {} clients already started, skipping duplicate startup", adapterDisplayName());
            return;
        }

        log.info("Starting mock {} clients", adapterDisplayName());

        try {
            SampleCommandRuntime.registerService(ClientSessionManager.class, clientSessionManager);
            if (SampleClientStateRegistry != null) {
                SampleCommandRuntime.registerService(SampleClientStateRegistry.class, SampleClientStateRegistry);
            }

            String baseUri = resolveBaseUri();
            log.info("{} target endpoint: {}", adapterDisplayName(), baseUri);

            List<Worker> workers = loadWorkers();
            if (workers == null || workers.isEmpty()) {
                log.warn("No SDK-registered {} mock workers found, skipping client startup", adapterId());
                started.set(false);
                return;
            }

            log.info("Found {} SDK-registered {} mock workers, establishing connections",
                    workers.size(),
                    adapterId());

            clientExecutor = Executors.newFixedThreadPool(Math.min(workers.size(), maxPoolSize));
            establishConnections(workers, baseUri);

            log.info("Mock {} clients started, active connections: {}",
                    adapterDisplayName(),
                    clientSessionManager.getClientCount());
        } catch (RuntimeException e) {
            started.set(false);
            throw e;
        } catch (Exception e) {
            started.set(false);
            throw new IllegalStateException("Failed to start mock " + adapterDisplayName() + " clients", e);
        }
    }

    /**
     * Discovers dev mock clients from SDK-registered worker resources.
     *
     * <p>Resource creation belongs to the SDK runtime. Concrete starters only
     * open transport clients for workers whose concrete adapter identity matches
     * this starter.
     */
    protected List<Worker> loadWorkers() {
        if (runtimeApplication == null) {
            logger().warn("MassSdkApplication is not available; cannot discover mock clients");
            return List.of();
        }
        return runtimeApplication.getAllWorkers().stream()
                .filter(this::isClientWorker)
                .toList();
    }

    protected boolean isClientWorker(Worker worker) {
        if (worker == null || worker.getWorkerId() == null || worker.getWorkerId().isBlank()) {
            return false;
        }
        String workerAdapterId = worker.getAdapterId();
        return workerAdapterId != null && adapterId().equalsIgnoreCase(workerAdapterId.trim());
    }

    protected void establishConnections(List<Worker> workers, String baseUri) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(workers.size());
        List<Future<?>> futures = new ArrayList<>();

        for (Worker worker : workers) {
            String workerId = worker.getWorkerId();
            Future<?> future = clientExecutor.submit(() -> {
                try {
                    connectWorkerWithRetry(workerId, baseUri);
                } catch (Exception e) {
                    logger().error("{} worker {} failed to connect", adapterDisplayName(), workerId, e);
                } finally {
                    latch.countDown();
                }
            });
            futures.add(future);
        }

        boolean completed = latch.await(workers.size() * (connectionTimeout + 5L), TimeUnit.SECONDS);
        if (!completed) {
            logger().warn("Some mock {} worker connections timed out", adapterId());
        }

        int successCount = clientSessionManager.getClientCount();
        int failCount = workers.size() - successCount;
        logger().info("{} connection summary: success={}, failed={}", adapterDisplayName(), successCount, failCount);
    }

    private void connectWorkerWithRetry(String workerId, String baseUri) {
        for (int attempt = 1; attempt <= retryAttempts; attempt++) {
            try {
                URI uri = new URI(baseUri);
                SampleWorkerClient client = createClient(uri, workerId, taskResultStatus);
                clientSessionManager.addClient(client);

                if (client.connectBlocking(connectionTimeout, TimeUnit.SECONDS)) {
                    logger().info("{} worker {} connected successfully ({}/{})",
                            adapterDisplayName(), workerId, attempt, retryAttempts);
                    return;
                }

                logger().warn("{} worker {} connection timed out ({}/{})",
                        adapterDisplayName(), workerId, attempt, retryAttempts);
            } catch (Exception e) {
                logger().warn("{} worker {} connection failed ({}/{}): {}",
                        adapterDisplayName(), workerId, attempt, retryAttempts, e.getMessage());
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

        logger().error("{} worker {} failed after {} retries", adapterDisplayName(), workerId, retryAttempts);
    }

    public String getConnectionStats() {
        int totalClients = clientSessionManager.getClientCount();
        return String.format("Active connections: %d", totalClients);
    }

    @PreDestroy
    public void shutdown() {
        Logger log = logger();
        MDC.clear();
        log.info("Shutting down mock {} clients", adapterDisplayName());
        started.set(false);

        Collection<SampleWorkerClient> clients = clientSessionManager.getAllClients();
        List<SampleWorkerClient> ownedClients = clients.stream()
                .filter(client -> adapterId().equalsIgnoreCase(client.adapterId()))
                .toList();
        log.info("Disconnecting {} mock clients", ownedClients.size());

        for (SampleWorkerClient client : ownedClients) {
            try {
                client.disconnect();
                log.debug("Client {} ({}) disconnected", client.getWorkerId(), client.adapterId());
            } catch (Exception e) {
                log.warn("Failed to disconnect client {} ({})", client.getWorkerId(), client.adapterId(), e);
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

        log.info("Mock {} clients stopped", adapterDisplayName());
        MDC.clear();
    }

    protected abstract Logger logger();

    protected abstract String adapterId();

    protected abstract String adapterDisplayName();

    protected abstract String resolveBaseUri();

    protected abstract SampleWorkerClient createClient(URI baseUri, String workerId, String taskResultStatus);
}

