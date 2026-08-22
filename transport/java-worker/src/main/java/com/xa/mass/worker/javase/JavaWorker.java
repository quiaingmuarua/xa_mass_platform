package com.xa.mass.worker.javase;

import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.runtime.WorkerConnectionOptions;
import com.xa.mass.worker.runtime.WorkerLifecycle;
import com.xa.mass.worker.runtime.WorkerPropertiesProvider;
import com.xa.mass.worker.runtime.WorkerRunController;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;

public final class JavaWorker implements WorkerLifecycle {

    private final WorkerRunController worker;
    private final JavaWorkerPlatform platform;

    private JavaWorker(
            WorkerRunController worker,
            JavaWorkerPlatform platform
    ) {
        this.worker = worker;
        this.platform = platform;
    }

    public static JavaWorker create(
            URI runtimeApiBaseUrl,
            String workerGroupId,
            String clientWorkerKey,
            WorkerTransportType transportType,
            WorkerPropertiesProvider workerProperties
    ) {
        return create(
                runtimeApiBaseUrl,
                workerGroupId,
                clientWorkerKey,
                transportType,
                workerProperties,
                Collections.emptyList(),
                WorkerConnectionOptions.defaults()
        );
    }

    public static JavaWorker create(
            URI runtimeApiBaseUrl,
            String workerGroupId,
            String clientWorkerKey,
            WorkerTransportType transportType,
            WorkerPropertiesProvider workerProperties,
            Collection<? extends WorkerEventDefinition<?>>
                    definitionExtensions
    ) {
        return create(
                runtimeApiBaseUrl,
                workerGroupId,
                clientWorkerKey,
                transportType,
                workerProperties,
                definitionExtensions,
                WorkerConnectionOptions.defaults()
        );
    }

    public static JavaWorker create(
            URI runtimeApiBaseUrl,
            String workerGroupId,
            String clientWorkerKey,
            WorkerTransportType transportType,
            WorkerPropertiesProvider workerProperties,
            Collection<? extends WorkerEventDefinition<?>>
                    definitionExtensions,
            WorkerConnectionOptions options
    ) {
        URI resolvedRuntimeApiBaseUrl = requireRuntimeApiBaseUrl(
                runtimeApiBaseUrl
        );
        String resolvedWorkerGroupId = requireNonBlank(
                workerGroupId,
                "workerGroupId"
        );
        String resolvedClientWorkerKey = requireNonBlank(
                clientWorkerKey,
                "clientWorkerKey"
        );
        WorkerTransportType resolvedTransportType =
                requireTextMessageTransportType(transportType);
        WorkerPropertiesProvider resolvedWorkerProperties =
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

        JavaWorkerPlatform platform =
                JavaWorkerPlatform.standalone(resolvedWorkerGroupId);
        try {
            WorkerRunController worker = JavaWorkerAssembly.assemble(
                    resolvedRuntimeApiBaseUrl,
                    resolvedWorkerGroupId,
                    resolvedClientWorkerKey,
                    resolvedTransportType,
                    resolvedWorkerProperties,
                    resolvedDefinitionExtensions,
                    resolvedOptions,
                    platform
            );
            return new JavaWorker(worker, platform);
        } catch (RuntimeException | Error failure) {
            platform.close();
            throw failure;
        }
    }

    @Override
    public void start() {
        worker.start();
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
        try {
            worker.close();
        } finally {
            platform.close();
        }
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

    private static WorkerTransportType requireTextMessageTransportType(
            WorkerTransportType value
    ) {
        if (value != WorkerTransportType.WEBSOCKET
                && value != WorkerTransportType.SOCKET) {
            throw new IllegalArgumentException(
                    "transportType must be WEBSOCKET or SOCKET"
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
