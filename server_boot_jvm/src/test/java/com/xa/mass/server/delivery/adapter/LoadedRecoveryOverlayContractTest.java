package com.xa.mass.server.delivery.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.xa.mass.server.worker.endpoint.WorkerEndpointDirectory;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.workerdelivery.adapter.netty.NettyWorkerDeliveryAdapterConfig;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context
        .ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LoadedRecoveryOverlayContractTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            ServerWorkerDeliveryAdapterConfiguration.class,
                            EndpointConfiguration.class
                    )
                    .withBean(
                            WorkerResourceCatalog.class,
                            () -> mock(WorkerResourceCatalog.class)
                    );

    @EnableConfigurationProperties(WorkerEndpointDirectory.class)
    static class EndpointConfiguration {}

    @Test
    void loadedRecoveryOverlayOverridesOnlyItsReportFields() {
        Path overlay = Path.of(System.getProperty(
                "xa.mass.repository.root"
        )).resolve(
                "integrations/worker-loaded-recovery/server-config/"
                        + "application-worker-loaded-recovery.yaml"
        );
        contextRunner.withInitializer(
                new ConfigDataApplicationContextInitializer()
        ).withPropertyValues(
                "spring.profiles.active=scenario-workers",
                "spring.config.additional-location=" + overlay.toUri()
        ).run(context -> {
            assertThat(context).hasNotFailed();
            NettyWorkerDeliveryAdapterConfig config = context.getBean(
                    ServerWorkerDeliveryAdapterProperties.class
            ).instances().get("scenario-websocket");
            assertThat(config.commandBackoff()).isEqualTo(
                    Duration.ofMillis(100)
            );
            assertThat(config.commandRetryCapacity()).isEqualTo(1000);
            assertThat(config.reportBackoff()).isEqualTo(
                    Duration.ofMillis(100)
            );
            assertThat(config.reportQueueCapacity()).isEqualTo(20_000);
        });
    }
}
