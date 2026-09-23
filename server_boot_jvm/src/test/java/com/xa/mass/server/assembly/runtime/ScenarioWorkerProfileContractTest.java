package com.xa.mass.server.assembly.runtime;

import com.xa.mass.kernel.assignment.RefillTarget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.worker.endpoint.WorkerEndpointDirectory;
import com.xa.mass.server.delivery.adapter
        .ServerWorkerDeliveryAdapterConfiguration;
import com.xa.mass.server.delivery.adapter
        .ServerWorkerDeliveryAdapterProperties;
import com.xa.mass.server.worker.group.WorkerGroupRegistrationService;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context
        .ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner
        .ApplicationContextRunner;
import org.springframework.context.LifecycleProcessor;

class ScenarioWorkerProfileContractTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withBean(com.xa.mass.server.project.ProjectTaskInitializer.class, () -> mock(com.xa.mass.server.project.ProjectTaskInitializer.class))
                    .withUserConfiguration(
                            ServerWorkerDeliveryAdapterConfiguration.class,
                            ServerWorkerAssemblyConfiguration.class,
                            EndpointConfiguration.class
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
                    );

    @EnableConfigurationProperties({WorkerEndpointDirectory.class,
            com.xa.mass.server.task.call.TaskRpcProperties.class})
    @org.springframework.context.annotation.Import(com.xa.mass.server.task.call.RefillTargetConfigurationConverter.class)
    static class EndpointConfiguration {}

    @Test
    void scenarioProfileSuppliesOpaqueJsonAndAdapterConfiguration() {
        contextRunner.withInitializer(
                new ConfigDataApplicationContextInitializer()
        ).withPropertyValues(
                "spring.profiles.active=scenario-workers"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            var defaults=context.getBean(com.xa.mass.server.task.call.TaskRpcProperties.class)
                    .refillByWorkerGroup();
            for (String group : List.of("scenario-string-utils-workers","scenario-phone-number-workers")) {
                assertThat(defaults.get(group)).containsExactly(
                        new RefillTarget("any", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()), 1000));
            }
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
}
