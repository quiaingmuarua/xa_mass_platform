package com.xa.mass.server.workerassembly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
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
                    );

    @Test
    void absentConfigurationCreatesAnInertAggregate() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(
                    ServerWorkerAssemblyProperties.class
            ).groupConfigJson()).isEqualTo("{}");
            assertThat(context.getBean(
                    ServerWorkerAssemblyProperties.class
            ).workerConfigJson()).isEqualTo("{}");
            assertThat(context.getBean(
                    ServerWorkerAssemblyProperties.class
            ).runtimeApiBaseUrl().toString())
                    .isEqualTo("http://127.0.0.1:18082");
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
            ServerWorkerAssemblyProperties properties = context.getBean(
                    ServerWorkerAssemblyProperties.class
            );
            assertThat(properties.groupConfigJson())
                    .contains("\"scenario-phone-number-workers\"")
                    .contains("\"scenario-string-utils-workers\"")
                    .contains("\"capability\":\"libphonenumber\"")
                    .contains("\"phonenumber.e164\"")
                    .contains("\"string.md5\"")
                    .doesNotContain("\"workers\"");
            assertThat(properties.workerConfigJson())
                    .contains("\"scenario-phone-number-workers\"")
                    .contains("\"scenario-string-utils-workers\"")
                    .contains("\"phonenumber.e164\"")
                    .contains("\"phonenumber.country\"")
                    .contains("\"string.md5\"")
                    .contains("\"string.base64.encode\"")
                    .doesNotContain("\"type\"")
                    .doesNotContain("\"workerGroupId\"")
                    .doesNotContain("\"attributes\"")
                    .contains("scenario-phone-number-worker-001")
                    .contains("scenario-phone-number-worker-010")
                    .contains("scenario-string-utils-worker-001")
                    .contains("scenario-string-utils-worker-010");
        });
    }

    @Test
    void malformedGroupOrWorkerJsonFailsDuringAssembly() {
        contextRunner.withPropertyValues(
                "xa.mass.worker-assembly.group-config-json={bad-json"
        ).run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues(
                "xa.mass.worker-assembly.worker-config-json={bad-json"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void oldSingleJsonConfigurationIsRejected() {
        contextRunner.withPropertyValues(
                "xa.mass.worker-assembly.config-json={}"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void catalogSummaryMayDriftFromScenarioDefinitions() {
        contextRunner.withPropertyValues(
                "xa.mass.worker-assembly.group-config-json="
                        + "{\"group\":{\"eventCodes\":[\"catalog.old\"]}}",
                "xa.mass.worker-assembly.worker-config-json="
                        + "{\"group\":{\"eventCodes\":[\"string.md5\"],"
                        + "\"endpointManagerId\":\"adapter\","
                        + "\"websocketUri\":\"ws://127.0.0.1:18083/connect\","
                        + "\"workers\":[{\"workerId\":\"worker-1\"}]}}"
        ).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void unknownServerConfigurationFieldIsRejected() {
        contextRunner.withPropertyValues(
                "xa.mass.worker-assembly.legacy=true"
        ).run(context -> assertThat(context).hasFailed());
    }
}
