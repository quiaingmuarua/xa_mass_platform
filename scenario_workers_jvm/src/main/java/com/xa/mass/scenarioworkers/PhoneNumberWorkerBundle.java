package com.xa.mass.scenarioworkers;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDeclaration;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeStatus;
import com.xa.mass.transport.client.okhttp.OkHttpTextWebSocketClient;
import com.xa.mass.worker.transport.websocket.WebSocketWorkerTransport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class PhoneNumberWorkerBundle
        implements ScenarioWorkerBundleLifecycle {

    private static final int GROUP_UPSERT_FAILED = 14002;
    private static final int WORKER_UPSERT_FAILED = 14003;
    private static final int WORKER_START_FAILED = 14004;
    private static final int WORKER_CONNECT_TIMEOUT = 14005;

    private final ScenarioWorkerBundleConfig config;
    private final WorkerResourceCatalog workerCatalog;
    private final WorkerRuntime workerRuntime;
    private final WorkerFactory workerFactory;
    private final List<WebSocketWorkerTransport> workers =
            new ArrayList<>();

    private boolean started;
    private boolean closed;

    PhoneNumberWorkerBundle(
            ScenarioWorkerBundleConfig config,
            WorkerResourceCatalog workerCatalog,
            WorkerRuntime workerRuntime
    ) {
        this(
                config,
                workerCatalog,
                workerRuntime,
                PhoneNumberWorkerBundle::createWorker
        );
    }

    PhoneNumberWorkerBundle(
            ScenarioWorkerBundleConfig config,
            WorkerResourceCatalog workerCatalog,
            WorkerRuntime workerRuntime,
            WorkerFactory workerFactory
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.workerCatalog = Objects.requireNonNull(
                workerCatalog,
                "workerCatalog"
        );
        this.workerRuntime = Objects.requireNonNull(
                workerRuntime,
                "workerRuntime"
        );
        this.workerFactory = Objects.requireNonNull(
                workerFactory,
                "workerFactory"
        );
    }

    @Override
    public String bundleId() {
        return config.bundleId();
    }

    @Override
    public void start() {
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException(
                        "Phone-number Worker bundle is closed"
                );
            }
            if (started) {
                return;
            }
            started = true;
        }

        try {
            upsertWorkerGroup();
            for (String workerId : workerIds()) {
                upsertWorker(workerId);
            }
            for (String workerId : workerIds()) {
                WebSocketWorkerTransport worker =
                        workerFactory.create(
                                workerId,
                                config
                        );
                workers.add(worker);
                worker.start();
            }
            awaitConnections();
        } catch (RuntimeException error) {
            closeAndSuppress(error);
            if (error instanceof ScenarioWorkerAssemblyException) {
                throw error;
            }
            throw new ScenarioWorkerAssemblyException(
                    WORKER_START_FAILED,
                    "phoneNumberWorkerBundle.start",
                    "Could not start Worker bundle "
                            + config.bundleId(),
                    error
            );
        }
    }

    List<String> workerIds() {
        List<String> ids = new ArrayList<>(config.workerCount());
        for (int index = 1;
                index <= config.workerCount();
                index++) {
            ids.add(workerId(
                    config.workerIdPrefix(),
                    index
            ));
        }
        return List.copyOf(ids);
    }

    @Override
    public void close() {
        List<WebSocketWorkerTransport> closing;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            closing = new ArrayList<>(workers);
            workers.clear();
        }
        Collections.reverse(closing);
        RuntimeException failure = null;
        for (WebSocketWorkerTransport worker : closing) {
            try {
                worker.close();
            } catch (RuntimeException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void upsertWorkerGroup() {
        WorkerRuntimeResult result = workerCatalog.upsertWorkerGroup(
                new WorkerGroupDescriptor(
                        config.workerGroupId(),
                        Map.of("capability", "libphonenumber"),
                        PhoneNumberCapability.EVENT_CODES,
                        Set.of("workerId")
                )
        );
        requireAccepted(
                result,
                GROUP_UPSERT_FAILED,
                "workerResourceCatalog.upsertWorkerGroup",
                config.workerGroupId()
        );
    }

    private void upsertWorker(String workerId) {
        WorkerRuntimeResult result = workerRuntime.upsertWorker(
                new WorkerDeclaration(
                        workerId,
                        config.workerGroupId(),
                        config.endpointManagerId(),
                        Map.of(
                                "runtime",
                                "java",
                                "capability",
                                "libphonenumber"
                        ),
                        Set.of()
                )
        );
        requireAccepted(
                result,
                WORKER_UPSERT_FAILED,
                "workerRuntime.upsertWorker",
                config.workerGroupId() + "/" + workerId
        );
    }

    private void awaitConnections() {
        long deadline = System.nanoTime()
                + config.connectTimeout().toNanos();
        while (System.nanoTime() < deadline) {
            if (workers.stream().allMatch(
                    WebSocketWorkerTransport::isConnected
            )) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new ScenarioWorkerAssemblyException(
                        WORKER_START_FAILED,
                        "phoneNumberWorkerBundle.awaitConnections",
                        "Interrupted while waiting for bundle "
                                + config.bundleId(),
                        error
                );
            }
        }
        long connected = workers.stream()
                .filter(WebSocketWorkerTransport::isConnected)
                .count();
        throw new ScenarioWorkerAssemblyException(
                WORKER_CONNECT_TIMEOUT,
                "phoneNumberWorkerBundle.awaitConnections",
                "Only "
                        + connected
                        + " of "
                        + config.workerCount()
                        + " Workers connected for bundle "
                        + config.bundleId()
        );
    }

    private void closeAndSuppress(RuntimeException failure) {
        try {
            close();
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static WebSocketWorkerTransport createWorker(
            String workerId,
            ScenarioWorkerBundleConfig config
    ) {
        return new WebSocketWorkerTransport(
                new OkHttpTextWebSocketClient(
                        config.workerWebSocketUri(),
                        config.requestTimeout(),
                        config.reconnectInterval()
                ),
                workerId,
                PhoneNumberCapability.definitions(workerId)
        );
    }

    private static void requireAccepted(
            WorkerRuntimeResult result,
            int errorCode,
            String operation,
            String resourceId
    ) {
        Objects.requireNonNull(result, "result");
        if (result.status() == WorkerRuntimeStatus.OK
                || result.status() == WorkerRuntimeStatus.NOOP) {
            return;
        }
        String reason = result.reason() == null
                ? ""
                : ": " + result.reason();
        throw new ScenarioWorkerAssemblyException(
                errorCode,
                operation,
                "Resource "
                        + resourceId
                        + " returned "
                        + result.status().wireValue()
                        + reason
        );
    }

    private static String workerId(
            String prefix,
            int oneBasedIndex
    ) {
        return prefix + String.format(
                Locale.ROOT,
                "%03d",
                oneBasedIndex
        );
    }

    @FunctionalInterface
    interface WorkerFactory {

        WebSocketWorkerTransport create(
                String workerId,
                ScenarioWorkerBundleConfig config
        );
    }
}
