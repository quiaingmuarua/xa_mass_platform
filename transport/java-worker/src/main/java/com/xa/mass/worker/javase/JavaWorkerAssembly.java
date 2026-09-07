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
import java.util.Objects;

/** Assembles one Java Worker against a caller-owned platform. */
final class JavaWorkerAssembly {

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
        WorkerPropertiesProvider completeProperties =
                JavaWorkerProperties.completeProvider(
                        clientWorkerKey,
                        liveProperties
                );
        return assembleComplete(
                runtimeApiBaseUrl,
                workerGroupId,
                transportType,
                liveProperties,
                completeProperties,
                definitionExtensions,
                options,
                platform
        );
    }

    static WorkerRunController assembleComplete(
            URI runtimeApiBaseUrl,
            String workerGroupId,
            WorkerTransportType transportType,
            WorkerPropertiesProvider liveProperties,
            WorkerPropertiesProvider completeProperties,
            Collection<? extends WorkerEventDefinition<?>>
                    definitionExtensions,
            WorkerConnectionOptions options,
            JavaWorkerPlatform platform
    ) {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(liveProperties, "workerProperties");
        Objects.requireNonNull(completeProperties, "completeProperties");
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
                        dispatcher,
                        liveProperties
                ),
                platform.controlExecutor()
        );
    }
}
