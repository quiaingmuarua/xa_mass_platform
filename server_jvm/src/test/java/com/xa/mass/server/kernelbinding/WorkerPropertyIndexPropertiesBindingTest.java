package com.xa.mass.server.kernelbinding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class WorkerPropertyIndexPropertiesBindingTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(
                    PropertyConfiguration.class
            );

    @Test
    void bindsRegistryJsonAndRejectsOldOrInvalidBindings() {
        runner.run(context -> assertThat(context.getBean(
                WorkerPropertyIndexProperties.class
        ).registry()).isEmpty());

        runner.withPropertyValues(
                "xa.mass.worker-property-index.registry-json="
                        + "{\"index.worker.region\":\"redis-hash\","
                        + "\"index.platform.pool\":\"redis-hash\"}"
        ).run(context -> assertThat(context.getBean(
                WorkerPropertyIndexProperties.class
        ).registry()).containsExactlyInAnyOrderEntriesOf(
                java.util.Map.of(
                        "index.worker.region", "redis-hash",
                        "index.platform.pool", "redis-hash"
                )
        ));

        runner.withPropertyValues(
                "xa.mass.worker-property-index.registry-json="
                        + "{\"region\":\"redis-hash\"}"
        ).run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(
                "xa.mass.worker-property-index.registry-json="
                        + "{\"index.worker.region\":\"unknown\"}"
        ).run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(
                "xa.mass.worker-property-index.implementations"
                        + "[index.worker.region]=redis-hash"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void canonicalFingerprintMatchesPython() {
        WorkerPropertyIndexProperties properties =
                new WorkerPropertyIndexProperties(
                        "{\"index.worker.region\":\"redis-hash\","
                                + "\"index.platform.pool\":"
                                + "\"redis-hash\"}"
                );

        assertThat(properties.registryJson()).isEqualTo(
                "{\"index.platform.pool\":\"redis-hash\","
                        + "\"index.worker.region\":\"redis-hash\"}"
        );
        assertThat(properties.fingerprint()).isEqualTo(
                "07c44a117d4fceb5da778ea7dac08522"
                        + "b7fbc93fccdcf1f996d232f1893adb7d"
        );
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WorkerPropertyIndexProperties.class)
    static class PropertyConfiguration {
    }
}
