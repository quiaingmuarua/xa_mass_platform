package com.xa.mass.scenario.appchecks;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.*;

class AppCheckStoragePropertiesTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppCheckStorageProperties.class)
    static class Binding { }
    final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Binding.class);

    @Test void pathAndSeedAreRequiredAndUnknownStorageFieldsAreRejected() {
        runner.run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("xa.mass.scenarios.app-checks.storage.path=./data/configuration")
                .run(context -> assertThat(context).hasFailed());
        var configured = runner.withPropertyValues("xa.mass.scenarios.app-checks.storage.path=./data/configuration",
                "xa.mass.scenarios.app-checks.storage.seed=classpath:app-checks/preview-configuration.json");
        configured.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AppCheckStorageProperties.class).storage().path()).isEqualTo("./data/configuration");
        });
        configured.withPropertyValues("xa.mass.scenarios.app-checks.storage.extra=true")
                .run(context -> assertThat(context).hasFailed());
        configured.withPropertyValues("xa.mass.scenarios.app-checks.storage.path=./data/configuration;AUTO_SERVER=TRUE")
                .run(context -> assertThat(context).hasFailed());
        configured.withPropertyValues("xa.mass.scenarios.app-checks.storage.path=${MISSING_APP_CHECKS_TEST_PATH}")
                .run(context -> assertThat(context).hasFailed());
    }
}
