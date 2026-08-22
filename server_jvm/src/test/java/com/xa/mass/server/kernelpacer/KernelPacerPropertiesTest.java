package com.xa.mass.server.kernelpacer;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.server.kernelredis.XaMassRedisProperties;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

class KernelPacerPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(KernelPacerConfiguration.class)
                    .withBean(
                            JsonMapper.class,
                            () -> JsonMapper.builder().build()
                    )
                    .withBean(
                            XaMassRedisProperties.class,
                            () -> new XaMassRedisProperties(
                                    URI.create("redis://example:6380/3"),
                                    "profile_managed"
                            )
                    )
                    .withPropertyValues(
                            "xa.mass.kernel-pacer.enabled=false",
                            "xa.mass.kernel-pacer.python-executable=python",
                            "xa.mass.kernel-pacer.working-directory=.",
                            "xa.mass.kernel-pacer.config-path=kernel.json",
                            "xa.mass.kernel-pacer.state-directory=state",
                            "xa.mass.kernel-pacer.startup-timeout=1s",
                            "xa.mass.kernel-pacer.shutdown-timeout=1s"
                    );

    @Test
    void bindsTheFiniteLifecycleConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(KernelPacerProperties.class).enabled())
                    .isFalse();
        });
    }

    @Test
    void rejectsUnknownLifecycleFields() {
        contextRunner.withPropertyValues(
                "xa.mass.kernel-pacer.extra-arguments=--unsafe"
        ).run(context -> assertThat(context).hasFailed());
    }
}
