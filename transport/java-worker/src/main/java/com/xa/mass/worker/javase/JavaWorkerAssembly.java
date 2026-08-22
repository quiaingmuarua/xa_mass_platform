package com.xa.mass.worker.javase;

import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerCommandDispatcher;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerManagementEventDefinitions;
import com.xa.mass.worker.runtime.WorkerControlPreparation;
import com.xa.mass.worker.runtime.TextMessageWorkerTransportFactory;
import com.xa.mass.worker.runtime.WorkerConnectionOptions;
import com.xa.mass.worker.runtime.WorkerPropertiesProvider;
import com.xa.mass.worker.runtime.WorkerRunController;

import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Assembles one Java Worker against a caller-owned platform. */
final class JavaWorkerAssembly {

    private static final String CLIENT_WORKER_KEY = "clientWorkerKey";

    private JavaWorkerAssembly() {
    }

    static WorkerRunController assemble(
            URI runtimeApiBaseUrl,
            String workerGroupId,
            String clientWorkerKey,
            WorkerTransportType transportType,
            WorkerPropertiesProvider workerProperties,
            Collection<? extends WorkerEventDefinition<?>>
                    definitionExtensions,
            WorkerConnectionOptions options,
            JavaWorkerPlatform platform
    ) {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(options, "options");
        WorkerPropertiesProvider liveProperties = Objects.requireNonNull(
                workerProperties,
                "workerProperties"
        );
        WorkerPropertiesProvider completeProperties = () -> {
            Map<String, Object> supplied = liveProperties.loadProperties();
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
            complete.put(CLIENT_WORKER_KEY, clientWorkerKey);
            complete.putAll(supplied);
            return complete;
        };
        WorkerCommandDispatcher dispatcher = WorkerCommandDispatcher.forWorker(
                WorkerManagementEventDefinitions.assemble(
                        liveProperties,
                        Objects.requireNonNull(
                                definitionExtensions,
                                "definitionExtensions"
                        )
                )
        );
        return new WorkerRunController(
                new WorkerControlPreparation(
                        workerGroupId,
                        transportType,
                        completeProperties,
                        platform.controlClient(runtimeApiBaseUrl),
                        options.requestTimeout()
                ),
                new TextMessageWorkerTransportFactory(
                        endpointUri -> platform.textClient(
                                transportType,
                                endpointUri,
                                options.requestTimeout(),
                                options.reconnectPolicy()
                        ),
                        dispatcher
                ),
                platform.controlExecutor()
        );
    }
}
