package com.xa.mass.server.workerassembly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.xa.mass.kernel.worker.WorkerPropertyIndexRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime;
import com.xa.mass.scenarioworkers.ScenarioWorkers;
import com.xa.mass.server.workerdelivery.adapter
        .ServerWorkerDeliveryAdapterConfiguration;
import com.xa.mass.workerdelivery.adapter.application
        .WorkerDeliveryAdapterManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context
        .ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner
        .ApplicationContextRunner;

class ServerWorkerAssemblyPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            ServerWorkerDeliveryAdapterConfiguration.class,
                            ServerWorkerAssemblyConfiguration.class
                    )
                    .withBean(
                            WorkerResourceCatalog.class,
                            () -> mock(WorkerResourceCatalog.class)
                    )
                    .withBean(
                            WorkerRuntime.class,
                            () -> mock(WorkerRuntime.class)
                    )
                    .withBean(
                            WorkerPropertyIndexRuntime.class,
                            () -> mock(WorkerPropertyIndexRuntime.class)
                    );

    @Test
    void absentConfigurationCreatesAnInertAggregate() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(
                    ServerWorkerAssemblyProperties.class
            ).configJson()).isEqualTo("{}");
            assertThat(context).hasSingleBean(ScenarioWorkers.class);
        });
    }

    @Test
    void scenarioProfileSuppliesOpaqueJsonAndAdapterConfiguration() {
        contextRunner.withInitializer(
                new ConfigDataApplicationContextInitializer()
        ).withPropertyValues(
                "spring.profiles.active=scenario-workers"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(
                    WorkerDeliveryAdapterManager.class
            ).adapters()).containsOnlyKeys("scenario-websocket");
            String configJson = context.getBean(
                    ServerWorkerAssemblyProperties.class
            ).configJson();
            assertThat(configJson)
                    .contains("\"type\": \"PHONE_NUMBER\"")
                    .contains("\"type\": \"STRING_UTILS\"")
                    .contains("scenario-phone-number-worker-001")
                    .contains("scenario-phone-number-worker-010")
                    .contains("scenario-string-utils-worker-001")
                    .contains("scenario-string-utils-worker-010");
        });
    }

    @Test
    void malformedOpaqueJsonFailsDuringScenarioAssembly() {
        contextRunner.withPropertyValues(
                "xa.mass.worker-assembly.config-json={bad-json"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void unknownServerConfigurationFieldIsRejected() {
        contextRunner.withPropertyValues(
                "xa.mass.worker-assembly.bundles.legacy=true"
        ).run(context -> assertThat(context).hasFailed());
    }
}
