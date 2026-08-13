package com.xa.mass.server.workerassembly;

import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.scenarioworkers.ScenarioWorkers;
import com.xa.mass.server.kernelbinding.TaskLifecycleCommands;
import com.xa.mass.workerdelivery.adapter.application
        .WorkerDeliveryAdapterManager;
import org.springframework.boot.context.properties
        .EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ServerWorkerAssemblyProperties.class)
public class ServerWorkerAssemblyConfiguration {

    @Bean
    ServerWorkerAssemblyManifest serverWorkerAssemblyManifest(
            ServerWorkerAssemblyProperties properties
    ) {
        return ServerWorkerAssemblyManifest.fromJson(
                properties.groupConfigJson()
        );
    }

    @Bean
    ServerWorkerGroupInitializer serverWorkerGroupInitializer(
            ServerWorkerAssemblyManifest manifest,
            WorkerResourceCatalog workerCatalog
    ) {
        return new ServerWorkerGroupInitializer(
                manifest,
                workerCatalog
        );
    }

    @Bean
    ServerWorkerTaskInitializer serverWorkerTaskInitializer(
            ServerWorkerAssemblyManifest manifest,
            TaskResourceCatalog taskCatalog,
            TaskRuntime taskRuntime,
            TaskLifecycleCommands taskLifecycle
    ) {
        return new ServerWorkerTaskInitializer(
                manifest,
                taskCatalog,
                taskRuntime,
                taskLifecycle
        );
    }

    @Bean
    ScenarioWorkers scenarioWorkers(
            ServerWorkerAssemblyProperties properties
    ) {
        return ScenarioWorkers.fromJson(
                properties.workerConfigJson(),
                properties.sandboxRoot(),
                properties.runtimeApiBaseUrl()
        );
    }

    @Bean
    ServerWorkerAssemblyLifecycleHost
    serverWorkerAssemblyLifecycleHost(
            ServerWorkerGroupInitializer groupInitializer,
            ServerWorkerTaskInitializer taskInitializer,
            WorkerDeliveryAdapterManager adapterManager,
            ScenarioWorkers scenarioWorkers
    ) {
        return new ServerWorkerAssemblyLifecycleHost(
                groupInitializer,
                taskInitializer,
                adapterManager,
                scenarioWorkers
        );
    }
}
