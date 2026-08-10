package com.xa.mass.worker.android;

import android.content.Context;

import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerCommandDispatcher;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.runtime.RegisteredWorkerPreparation;
import com.xa.mass.worker.runtime.TextMessageWorkerTransportFactory;
import com.xa.mass.worker.runtime.WorkerConnectionOptions;
import com.xa.mass.worker.runtime.WorkerLifecycle;
import com.xa.mass.worker.runtime.WorkerPropertiesProvider;
import com.xa.mass.worker.runtime.WorkerRunController;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class AndroidWorker implements WorkerLifecycle {

    private static final String CLIENT_WORKER_KEY = "clientWorkerKey";
    private static final Set<String> ACTIVE_COORDINATES =
            ConcurrentHashMap.newKeySet();

    private final Object lock = new Object();
    private final String processCoordinate;
    private final WorkerRunController worker;
    private final AndroidWorkerPlatform platform;
    private final WorkerLifecycle.Listener lifecycleListener;
    private boolean processLeaseHeld;
    private boolean closed;

    private AndroidWorker(
            String processCoordinate,
            WorkerRunController worker,
            AndroidWorkerPlatform platform
    ) {
        this.processCoordinate = processCoordinate;
        this.worker = worker;
        this.platform = platform;
        lifecycleListener = this::releaseProcessLeaseWhenStopped;
        worker.addListener(lifecycleListener);
    }

    public static AndroidWorker create(
            Context applicationContext,
            URI runtimeApiBaseUrl,
            String workerGroupId,
            AndroidWorkerProperties workerProperties
    ) {
        return create(
                applicationContext,
                runtimeApiBaseUrl,
                workerGroupId,
                workerProperties,
                Collections.emptyList(),
                WorkerConnectionOptions.defaults()
        );
    }

    public static AndroidWorker create(
            Context applicationContext,
            URI runtimeApiBaseUrl,
            String workerGroupId,
            AndroidWorkerProperties workerProperties,
            Collection<? extends WorkerEventDefinition<?>>
                    definitionExtensions
    ) {
        return create(
                applicationContext,
                runtimeApiBaseUrl,
                workerGroupId,
                workerProperties,
                definitionExtensions,
                WorkerConnectionOptions.defaults()
        );
    }

    public static AndroidWorker create(
            Context applicationContext,
            URI runtimeApiBaseUrl,
            String workerGroupId,
            AndroidWorkerProperties workerProperties,
            Collection<? extends WorkerEventDefinition<?>>
                    definitionExtensions,
            WorkerConnectionOptions options
    ) {
        Context resolvedContext = requireApplicationContext(
                applicationContext
        );
        URI resolvedRuntimeApiBaseUrl = requireRuntimeApiBaseUrl(
                runtimeApiBaseUrl
        );
        String resolvedWorkerGroupId = requireNonBlank(
                workerGroupId,
                "workerGroupId"
        );
        AndroidWorkerProperties resolvedWorkerProperties =
                Objects.requireNonNull(
                        workerProperties,
                        "workerProperties"
                );
        Collection<? extends WorkerEventDefinition<?>>
                resolvedDefinitionExtensions = Objects.requireNonNull(
                        definitionExtensions,
                        "definitionExtensions"
                );
        WorkerConnectionOptions resolvedOptions = Objects.requireNonNull(
                options,
                "options"
        );
        WorkerCommandDispatcher dispatcher =
                WorkerCommandDispatcher.forWorker(
                        resolvedDefinitionExtensions
                );
        AndroidClientWorkerKeyStore clientKeyStore =
                new AndroidClientWorkerKeyStore(
                        resolvedContext,
                        resolvedWorkerGroupId
                );
        AndroidWorkerIdentityStore identityStore =
                new AndroidWorkerIdentityStore(
                        resolvedContext,
                        resolvedWorkerGroupId
                );
        WorkerPropertiesProvider completeProperties = () -> {
            Map<String, Object> supplied =
                    resolvedWorkerProperties.getProperties(
                            resolvedContext
                    );
            if (supplied == null) {
                throw new IllegalArgumentException(
                        "workerProperties must be present"
                );
            }
            if (supplied.containsKey(CLIENT_WORKER_KEY)) {
                throw new IllegalArgumentException(
                        "workerProperties must not override "
                                + CLIENT_WORKER_KEY
                );
            }
            Map<String, Object> complete = new LinkedHashMap<>();
            complete.put(
                    CLIENT_WORKER_KEY,
                    clientKeyStore.loadOrCreate()
            );
            complete.putAll(supplied);
            return complete;
        };

        AndroidWorkerPlatform platform =
                AndroidWorkerPlatform.create(resolvedWorkerGroupId);
        WorkerRunController worker = null;
        try {
            worker = new WorkerRunController(
                    new RegisteredWorkerPreparation(
                            resolvedWorkerGroupId,
                            WorkerTransportType.WEBSOCKET,
                            identityStore,
                            completeProperties,
                            platform.controlClient(
                                    resolvedRuntimeApiBaseUrl
                            ),
                            resolvedOptions.requestTimeout()
                    ),
                    new TextMessageWorkerTransportFactory(
                            endpointUri -> platform.textClient(
                                    endpointUri,
                                    resolvedOptions.requestTimeout(),
                                    resolvedOptions.reconnectPolicy()
                            ),
                            dispatcher
                    ),
                    platform.controlExecutor()
            );
            return new AndroidWorker(
                    resolvedContext.getPackageName()
                            + "\n" + resolvedWorkerGroupId,
                    worker,
                    platform
            );
        } catch (RuntimeException | Error failure) {
            if (worker != null) {
                try {
                    worker.close();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            try {
                platform.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    @Override
    public void start() {
        boolean leaseAcquired = false;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("AndroidWorker is closed");
            }
            if (!processLeaseHeld) {
                if (!ACTIVE_COORDINATES.add(processCoordinate)) {
                    throw new IllegalStateException(
                            "An Android Worker for this application and "
                                    + "WorkerGroup is already active"
                    );
                }
                processLeaseHeld = true;
                leaseAcquired = true;
            }
        }
        try {
            worker.start();
        } catch (RuntimeException | Error error) {
            if (leaseAcquired) {
                releaseProcessLease();
            }
            throw error;
        } finally {
            if (worker.snapshot().state() == State.STOPPED) {
                releaseProcessLease();
            }
        }
    }

    @Override
    public void stop() {
        worker.stop();
    }

    @Override
    public Snapshot snapshot() {
        return worker.snapshot();
    }

    @Override
    public void addListener(Listener listener) {
        worker.addListener(listener);
    }

    @Override
    public void removeListener(Listener listener) {
        worker.removeListener(listener);
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        try {
            worker.close();
        } finally {
            worker.removeListener(lifecycleListener);
            releaseProcessLease();
            platform.close();
        }
    }

    private void releaseProcessLeaseWhenStopped(Snapshot snapshot) {
        synchronized (lock) {
            if (snapshot.state() != State.STOPPED
                    || worker.snapshot().state() != State.STOPPED) {
                return;
            }
            releaseProcessLeaseLocked();
        }
    }

    private void releaseProcessLease() {
        synchronized (lock) {
            releaseProcessLeaseLocked();
        }
    }

    private void releaseProcessLeaseLocked() {
        if (processLeaseHeld) {
            processLeaseHeld = false;
            ACTIVE_COORDINATES.remove(processCoordinate);
        }
    }

    private static Context requireApplicationContext(Context value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "applicationContext must be present"
            );
        }
        Context resolved = value.getApplicationContext();
        return resolved == null ? value : resolved;
    }

    private static URI requireRuntimeApiBaseUrl(URI value) {
        if (value == null
                || !value.isAbsolute()
                || value.getHost() == null
                || (!("http".equalsIgnoreCase(value.getScheme()))
                && !("https".equalsIgnoreCase(value.getScheme())))) {
            throw new IllegalArgumentException(
                    "runtimeApiBaseUrl must be an absolute HTTP(S) URI"
            );
        }
        return value;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value;
    }
}
