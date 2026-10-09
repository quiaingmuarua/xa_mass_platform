package com.xa.mass.server.assembly.runtime;

import com.xa.mass.server.worker.group.WorkerGroupRegistrationService;
import com.xa.mass.server.delivery.adapter.WorkerRouteVerificationBatcher;
import com.xa.mass.workerdelivery.adapter.application
        .WorkerDeliveryAdapterManager;
import org.springframework.boot.context.properties
        .EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ServerWorkerAssemblyProperties.class, com.xa.mass.server.project.ProjectAssemblyProperties.class})
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

    @Bean(destroyMethod = "stop")
    ServerConfiguredRuntimeLifecycleHost
    serverConfiguredRuntimeLifecycleHost(
            ServerWorkerGroupInitializer groupInitializer,
            ProjectWorkerRequirementsValidator requirements,
            com.xa.mass.server.project.ProjectTaskInitializer projectInitializer,
            WorkerDeliveryAdapterManager adapterManager,
            WorkerRouteVerificationBatcher routeVerificationBatcher
    ) {
        return new ServerConfiguredRuntimeLifecycleHost(
                groupInitializer,
                requirements,
                projectInitializer,
                adapterManager,
                routeVerificationBatcher
        );
    }

    @Bean
    ProjectWorkerRequirementsValidator projectWorkerRequirementsValidator(
            com.xa.mass.server.project.ProjectDirectory projects,
            com.xa.mass.kernel.worker.WorkerResourceCatalog workers,
            com.xa.mass.server.assembly.matching.MatchingProperties matching,
            java.util.List<com.xa.mass.server.project.ProjectWorkerRequirements> requirements) {
        return new ProjectWorkerRequirementsValidator(projects, workers, matching, requirements);
    }
}
