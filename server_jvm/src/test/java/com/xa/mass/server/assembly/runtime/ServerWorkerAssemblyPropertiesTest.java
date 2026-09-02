package com.xa.mass.server.assembly.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.worker.binding.WorkerEndpointDirectory;
import com.xa.mass.server.worker.binding.WorkerTransportType;
import com.xa.mass.server.delivery.adapter
        .ServerWorkerDeliveryAdapterConfiguration;
import com.xa.mass.server.delivery.adapter
        .ServerWorkerDeliveryAdapterProperties;
import com.xa.mass.server.worker.group.WorkerGroupRegistrationService;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context
        .ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner
        .ApplicationContextRunner;
import org.springframework.context.LifecycleProcessor;

class ServerWorkerAssemblyPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            ServerWorkerDeliveryAdapterConfiguration.class,
                            ServerWorkerAssemblyConfiguration.class
                    )
                    .withBean(
                            "lifecycleProcessor",
                            LifecycleProcessor.class,
                            () -> mock(LifecycleProcessor.class)
                    )
                    .withBean(
                            WorkerResourceCatalog.class,
                            () -> mock(WorkerResourceCatalog.class)
                    )
                    .withBean(
                            WorkerGroupRegistrationService.class,
                            () -> mock(
                                    WorkerGroupRegistrationService.class
                            )
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
                    ServerWorkerAssemblyManifest.class
            ).workerGroups()).isEmpty();
            assertThat(context).hasSingleBean(
                    ServerConfiguredRuntimeLifecycleHost.class
            );
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
                    ServerWorkerDeliveryAdapterProperties.class
            ).instances()).containsOnlyKeys("scenario-websocket");
            assertThat(context.getEnvironment().getProperty(
                    "xa.mass.kernel-pacer.preset"
            )).isEqualTo("SCENARIO_LAB");
            ServerWorkerAssemblyProperties properties = context.getBean(
                    ServerWorkerAssemblyProperties.class
            );
            assertThat(properties.groupConfigJson())
                    .contains("\"scenario-phone-number-workers\"")
                    .contains("\"scenario-string-utils-workers\"")
                    .contains("\"android-demo-workers\"")
                    .contains("\"capability\":\"libphonenumber\"")
                    .contains("\"capability\":\"android-demo-capabilities\"")
                    .contains("\"extension.worker.phonenumber.e164\"")
                    .contains("\"extension.worker.string.md5\"")
                    .contains("\"extension.worker.android.state.read\"")
                    .contains("\"extension.worker.android.battery.read\"")
                    .contains("\"extension.worker.lab.delay\"")
                    .contains("\"extension.worker.lab.fail\"")
                    .doesNotContain("\"workers\"");
            assertThat(context.getBean(
                    ServerWorkerAssemblyManifest.class
            ).workerGroups()).extracting(
                    descriptor -> descriptor.workerGroupId()
            ).containsExactly(
                    "scenario-phone-number-workers",
                    "scenario-string-utils-workers",
                    "android-demo-workers"
            );
            Map<String, Object> groups = Jsons.parseObject(
                    properties.groupConfigJson()
            );
            Map<?, ?> androidGroup = (Map<?, ?>) groups.get(
                    "android-demo-workers"
            );
            assertThat(androidGroup.get("eventCodes"))
                    .isEqualTo(List.of(
                            "extension.worker.android.state.read",
                            "extension.worker.android.battery.read",
                            "extension.worker.android.string.digest",
                            "extension.worker.lab.delay",
                            "extension.worker.lab.fail"
                    ));
        });
    }

    @Test
    void malformedGroupJsonFailsDuringAssembly() {
        contextRunner.withPropertyValues(
                "xa.mass.worker-assembly.group-config-json={bad-json"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void oldSingleJsonConfigurationIsRejected() {
        contextRunner.withPropertyValues(
                "xa.mass.worker-assembly.config-json={}"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void theRemovedTaskCallAllowlistIsRejected() {
        contextRunner.withPropertyValues(
                "xa.mass.worker-assembly.group-config-json="
                        + "{\"group\":{\"eventCodes\":[]}}",
                "xa.mass.worker-assembly.task-call-worker-group-ids[0]=group"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void unknownServerConfigurationFieldIsRejected() {
        contextRunner.withPropertyValues(
                "xa.mass.worker-assembly.legacy=true"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void removedWorkerHostConfigurationIsRejected() {
        for (String property : List.of(
                "capability-assembly-json={}",
                "runtime-api-base-url=http://127.0.0.1:18082",
                "sandbox-root=data/scenario-workers"
        )) {
            contextRunner.withPropertyValues(
                    "xa.mass.worker-assembly." + property
            ).run(context -> assertThat(context).hasFailed());
        }
    }
}
