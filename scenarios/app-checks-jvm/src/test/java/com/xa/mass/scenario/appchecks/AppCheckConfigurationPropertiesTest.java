package com.xa.mass.scenario.appchecks;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.*;

class AppCheckConfigurationPropertiesTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppCheckConfigurationProperties.class)
    static class Binding { }
    final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Binding.class);

    @Test void pathAndSeedAreRequiredAndOldOrUnknownFieldsAreRejected() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AppCheckConfigurationProperties.class).enabled()).isFalse();
        });
        runner.withPropertyValues("xa.mass.scenarios.app-checks.enabled=true").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("xa.mass.scenarios.app-checks.enabled=unknown").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("xa.mass.scenarios.app-checks.extra=true").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("xa.mass.scenarios.app-checks.configuration.path=./data/configuration.json")
                .run(context -> assertThat(context).hasFailed());
        var configured = runner.withPropertyValues("xa.mass.scenarios.app-checks.enabled=true", "xa.mass.scenarios.app-checks.configuration.path=./data/configuration.json",
                "xa.mass.scenarios.app-checks.configuration.seed=classpath:app-checks/preview-configuration.json");
        configured.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AppCheckConfigurationProperties.class).configuration().path()).isEqualTo("./data/configuration.json");
        });
        configured.withPropertyValues("xa.mass.scenarios.app-checks.configuration.extra=true")
                .run(context -> assertThat(context).hasFailed());
        configured.withPropertyValues("xa.mass.scenarios.app-checks.storage.path=./data/old-database")
                .run(context -> assertThat(context).hasFailed());
        configured.withPropertyValues("xa.mass.scenarios.app-checks.configuration.path=${MISSING_APP_CHECKS_TEST_PATH}")
                .run(context -> assertThat(context).hasFailed());
    }
}
