package com.xa.mass.server.assembly.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.worker.endpoint.WorkerEndpointDirectory;
import com.xa.mass.server.delivery.adapter
        .ServerWorkerDeliveryAdapterConfiguration;
import com.xa.mass.server.delivery.adapter
        .ServerWorkerDeliveryAdapterProperties;
import com.xa.mass.server.worker.group.WorkerGroupRegistrationService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner
        .ApplicationContextRunner;
import org.springframework.context.LifecycleProcessor;

class ServerWorkerAssemblyPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
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

    @EnableConfigurationProperties(WorkerEndpointDirectory.class)
    static class EndpointConfiguration {}

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
