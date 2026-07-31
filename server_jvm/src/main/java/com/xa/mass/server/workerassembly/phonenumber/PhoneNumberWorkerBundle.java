package com.xa.mass.server.workerassembly.phonenumber;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDeclaration;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeStatus;
import com.xa.mass.server.workerassembly.ServerWorkerAssemblyProperties
        .BundleProperties;
import com.xa.mass.server.workerassembly.WorkerAssemblyException;
import com.xa.mass.transport.client.okhttp.OkHttpTextWebSocketClient;
import com.xa.mass.worker.transport.websocket.WebSocketWorkerTransport;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class PhoneNumberWorkerBundle implements AutoCloseable {

    private static final int GROUP_UPSERT_FAILED = 14002;
    private static final int WORKER_UPSERT_FAILED = 14003;
    private static final int WORKER_START_FAILED = 14004;
    private static final int WORKER_CONNECT_TIMEOUT = 14005;

    private final String bundleId;
    private final BundleProperties properties;
    private final URI websocketUri;
    private final WorkerResourceCatalog workerCatalog;
    private final WorkerRuntime workerRuntime;
    private final WorkerFactory workerFactory;
    private final List<WebSocketWorkerTransport> workers =
            new ArrayList<>();

    private boolean started;
    private boolean closed;

    public PhoneNumberWorkerBundle(
            String bundleId,
            BundleProperties properties,
            URI websocketUri,
            WorkerResourceCatalog workerCatalog,
            WorkerRuntime workerRuntime
    ) {
        this(
                bundleId,
                properties,
                websocketUri,
                workerCatalog,
                workerRuntime,
                PhoneNumberWorkerBundle::createWorker
        );
    }

    PhoneNumberWorkerBundle(
            String bundleId,
            BundleProperties properties,
            URI websocketUri,
            WorkerResourceCatalog workerCatalog,
            WorkerRuntime workerRuntime,
            WorkerFactory workerFactory
    ) {
        this.bundleId = requireNonBlank(bundleId, "bundleId");
        this.properties = Objects.requireNonNull(
                properties,
                "properties"
        );
        this.websocketUri = requireWebSocketUri(websocketUri);
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

    public String bundleId() {
        return bundleId;
    }

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
                                properties,
                                websocketUri
                        );
                workers.add(worker);
                worker.start();
            }
            awaitConnections();
        } catch (RuntimeException error) {
            closeAndSuppress(error);
            if (error instanceof WorkerAssemblyException) {
                throw error;
            }
            throw new WorkerAssemblyException(
                    WORKER_START_FAILED,
                    "phoneNumberWorkerBundle.start",
                    "Could not start Worker bundle " + bundleId,
                    error
            );
        }
    }

    public List<String> workerIds() {
        List<String> ids = new ArrayList<>(properties.workerCount());
        for (int index = 1;
                index <= properties.workerCount();
                index++) {
            ids.add(workerId(
                    properties.workerIdPrefix(),
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
                        properties.workerGroupId(),
                        Map.of("capability", "libphonenumber"),
                        Set.of(PhoneNumberCapability.EVENT_CODE),
                        Set.of("workerId")
                )
        );
        requireAccepted(
                result,
                GROUP_UPSERT_FAILED,
                "workerResourceCatalog.upsertWorkerGroup",
                properties.workerGroupId()
        );
    }

    private void upsertWorker(String workerId) {
        WorkerRuntimeResult result = workerRuntime.upsertWorker(
                new WorkerDeclaration(
                        workerId,
                        properties.workerGroupId(),
                        properties.adapterId(),
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
                properties.workerGroupId() + "/" + workerId
        );
    }

    private void awaitConnections() {
        long deadline = System.nanoTime()
                + properties.connectTimeout().toNanos();
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
                throw new WorkerAssemblyException(
                        WORKER_START_FAILED,
                        "phoneNumberWorkerBundle.awaitConnections",
                        "Interrupted while waiting for bundle "
                                + bundleId,
                        error
                );
            }
        }
        long connected = workers.stream()
                .filter(WebSocketWorkerTransport::isConnected)
                .count();
        throw new WorkerAssemblyException(
                WORKER_CONNECT_TIMEOUT,
                "phoneNumberWorkerBundle.awaitConnections",
                "Only "
                        + connected
                        + " of "
                        + properties.workerCount()
                        + " Workers connected for bundle "
                        + bundleId
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
            BundleProperties properties,
            URI websocketUri
    ) {
        return new WebSocketWorkerTransport(
                new OkHttpTextWebSocketClient(
                        websocketUri,
                        properties.requestTimeout(),
                        properties.reconnectInterval()
                ),
                workerId,
                List.of(PhoneNumberCapability.definition(workerId))
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
        throw new WorkerAssemblyException(
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

    private static String requireNonBlank(
            String value,
            String name
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    name + " must be non-blank"
            );
        }
        return value;
    }

    private static URI requireWebSocketUri(URI value) {
        Objects.requireNonNull(value, "websocketUri");
        String scheme = value.getScheme();
        if (!value.isAbsolute()
                || value.getHost() == null
                || (!"ws".equalsIgnoreCase(scheme)
                && !"wss".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException(
                    "websocketUri must be an absolute ws/wss URI"
            );
        }
        return value;
    }

    @FunctionalInterface
    interface WorkerFactory {

        WebSocketWorkerTransport create(
                String workerId,
                BundleProperties properties,
                URI websocketUri
        );
    }
}
