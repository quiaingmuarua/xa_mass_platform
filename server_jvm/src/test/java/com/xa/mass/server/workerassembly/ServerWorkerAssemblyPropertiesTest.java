package com.xa.mass.server.workerassembly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.scenarioworkers.ScenarioWorkers;
import com.xa.mass.server.kernelbinding.TaskLifecycleCommands;
import com.xa.mass.server.workerbinding.WorkerEndpointDirectory;
import com.xa.mass.server.workerbinding.WorkerTransportType;
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
                            TaskResourceCatalog.class,
                            () -> mock(TaskResourceCatalog.class)
                    )
                    .withBean(
                            TaskRuntime.class,
                            () -> mock(TaskRuntime.class)
                    )
                    .withBean(
                            TaskLifecycleCommands.class,
                            () -> mock(TaskLifecycleCommands.class)
                    )
                    .withBean(WorkerEndpointDirectory.class, () -> {
                        WorkerEndpointDirectory directory = mock(
                                WorkerEndpointDirectory.class
                        );
                        org.mockito.Mockito.when(directory.contains(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers
                                        .any(WorkerTransportType.class)
                        )).thenReturn(true);
                        return directory;
                    });

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
            assertThat(context.getBean(
                    ServerWorkerAssemblyProperties.class
            ).sandboxRoot()).isEqualTo("data/scenario-workers");
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
                    .contains("\"android-demo-workers\"")
                    .contains("\"capability\":\"libphonenumber\"")
                    .contains("\"capability\":\"android-demo-state\"")
                    .contains("\"phonenumber.e164\"")
                    .contains("\"string.md5\"")
                    .contains("\"android.demo.state.read\"")
                    .contains("\"android.demo.battery.read\"")
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
                    .doesNotContain("\"workers\"")
                    .doesNotContain("\"android-demo-workers\"")
                    .doesNotContain("android.demo.state.read")
                    .doesNotContain("clientWorkerKey")
                    .doesNotContain("sandboxDirectory");
            assertThat(properties.sandboxRoot())
                    .isEqualTo("data/scenario-workers");
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
                        + "{\"group\":{\"eventCodes\":[\"string.md5\"]}}"
        ).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void unknownServerConfigurationFieldIsRejected() {
        contextRunner.withPropertyValues(
                "xa.mass.worker-assembly.legacy=true"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void blankSandboxRootIsRejected() {
        contextRunner.withPropertyValues(
                "xa.mass.worker-assembly.sandbox-root= "
        ).run(context -> assertThat(context).hasFailed());
    }
}
