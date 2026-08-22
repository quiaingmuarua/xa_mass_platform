package com.xa.mass.server.kernelredis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class XaMassRedisPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(KernelRedisConfiguration.class)
                    .withPropertyValues(
                            "xa.mass.redis.url=redis://localhost:6379/15"
                    );

    @Test
    void bindsValidatedScopeAndFixedRoot() {
        contextRunner.withPropertyValues(
                "xa.mass.redis.scope=profile_default"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(XaMassRedisProperties.class)
                    .keyspace().base())
                    .isEqualTo("xa_mass:profile_default");
        });
    }

    @Test
    void rejectsUnsafeScopeBeforeCreatingRedisClient() {
        contextRunner.withPropertyValues(
                "xa.mass.redis.scope=profile:default"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseMessage(
                            "Redis scope must match profile_[a-z0-9_]+ "
                                    + "or test_[a-z0-9_]+"
                    );
        });
    }
}
