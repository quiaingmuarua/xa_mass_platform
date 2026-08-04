package com.xa.mass.server.workerassembly;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerPropertyIndexRuntime;
import com.xa.mass.kernel.worker.WorkerRuntime;
import com.xa.mass.scenarioworkers.PhoneNumberWorkerEvents;
import com.xa.mass.scenarioworkers.ScenarioWorkers;
import com.xa.mass.scenarioworkers.StringUtilityWorkerEvents;
import com.xa.mass.workerdelivery.adapter.application
        .WorkerDeliveryAdapterManager;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties
        .EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ServerWorkerAssemblyProperties.class)
public class ServerWorkerAssemblyConfiguration {

    @Bean
    ScenarioWorkers scenarioWorkers(
            ServerWorkerAssemblyProperties properties,
            WorkerResourceCatalog workerCatalog,
            WorkerRuntime workerRuntime,
            WorkerPropertyIndexRuntime propertyIndex
    ) {
        return ScenarioWorkers.fromJson(
                properties.configJson(),
                scenarioDefinitionsByEventCode(),
                workerCatalog,
                workerRuntime,
                propertyIndex
        );
    }

    @Bean
    ServerWorkerAssemblyLifecycleHost
    serverWorkerAssemblyLifecycleHost(
            WorkerDeliveryAdapterManager adapterManager,
            ScenarioWorkers scenarioWorkers
    ) {
        return new ServerWorkerAssemblyLifecycleHost(
                adapterManager,
                scenarioWorkers
        );
    }

    private static Map<String, WorkerEventDefinition<?>>
    scenarioDefinitionsByEventCode() {
        Map<String, WorkerEventDefinition<?>> definitions =
                new LinkedHashMap<>();
        addDefinitions(definitions, PhoneNumberWorkerEvents.definitions());
        addDefinitions(definitions, StringUtilityWorkerEvents.definitions());
        return Collections.unmodifiableMap(definitions);
    }

    private static void addDefinitions(
            Map<String, WorkerEventDefinition<?>> target,
            List<WorkerEventDefinition<?>> definitions
    ) {
        for (WorkerEventDefinition<?> definition : definitions) {
            WorkerEventDefinition<?> existing = target.putIfAbsent(
                    definition.eventCode(),
                    definition
            );
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate Scenario Worker eventCode: "
                                + definition.eventCode()
                );
            }
        }
    }
}
