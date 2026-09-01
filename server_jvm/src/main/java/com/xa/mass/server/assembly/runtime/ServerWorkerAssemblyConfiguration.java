package com.xa.mass.server.assembly.runtime;

import com.xa.mass.server.worker.group.WorkerGroupRegistrationService;
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
            WorkerGroupRegistrationService registrations
    ) {
        return new ServerWorkerGroupInitializer(
                manifest,
                registrations
        );
    }

    @Bean
    ServerConfiguredRuntimeLifecycleHost
    serverConfiguredRuntimeLifecycleHost(
            ServerWorkerGroupInitializer groupInitializer,
            WorkerDeliveryAdapterManager adapterManager
    ) {
        return new ServerConfiguredRuntimeLifecycleHost(
                groupInitializer,
                adapterManager
        );
    }
}
