package com.xa.mass.server.workerassembly;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.scenarioworkers.ScenarioWorkers;
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
    ServerWorkerGroupInitializer serverWorkerGroupInitializer(
            ServerWorkerAssemblyProperties properties,
            WorkerResourceCatalog workerCatalog
    ) {
        return ServerWorkerGroupInitializer.fromJson(
                properties.groupConfigJson(),
                workerCatalog
        );
    }

    @Bean
    ScenarioWorkers scenarioWorkers(
            ServerWorkerAssemblyProperties properties
    ) {
        return ScenarioWorkers.fromJson(
                properties.workerConfigJson(),
                properties.runtimeApiBaseUrl()
        );
    }

    @Bean
    ServerWorkerAssemblyLifecycleHost
    serverWorkerAssemblyLifecycleHost(
            ServerWorkerGroupInitializer groupInitializer,
            WorkerDeliveryAdapterManager adapterManager,
            ScenarioWorkers scenarioWorkers
    ) {
        return new ServerWorkerAssemblyLifecycleHost(
                groupInitializer,
                adapterManager,
                scenarioWorkers
        );
    }
}
