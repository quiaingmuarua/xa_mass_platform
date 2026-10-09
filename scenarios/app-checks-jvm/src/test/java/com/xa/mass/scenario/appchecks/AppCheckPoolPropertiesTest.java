package com.xa.mass.scenario.appchecks;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.assertThat;

class AppCheckPoolPropertiesTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppCheckPoolProperties.class)
    static class Binding {}
    final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Binding.class);
    final String prefix = "xa.mass.worker-pools.assignment-window.groups.";

    ApplicationContextRunner configured() {
        return runner.withPropertyValues(prefix + "app-a-sim.window-millis=60000", prefix + "app-a-sim.max-count=10",
                prefix + "app-b-sim.window-millis=30000", prefix + "app-b-sim.max-count=5");
    }

    @Test void onePoolConfigurationDrivesBothTheDeclarationAndProjections() {
        configured().run(context -> {
            assertThat(context).hasNotFailed();
            var config = context.getBean(AppCheckPoolProperties.class);
            var assembly = new AppCheckScenarioConfiguration();
            var definition = assembly.appCheckWindowPool(config);
            assertThat(definition.poolName()).isEqualTo(AppCheckWorkerSupply.POOL);
            assertThat(definition.functionName()).isEqualTo(AppCheckWorkerSupply.FUNCTION);
            assertThat(definition.timestampProperty()).isEqualTo(AppCheckWorkerSupply.LAST);
            assertThat(definition.countProperty()).isEqualTo(AppCheckWorkerSupply.COUNT);
            var times = java.util.List.of(29_999L, 30_000L);
            assertThat(assembly.appAAssignmentProjection(config).project().apply(java.util.Map.of(), times))
                    .containsEntry(definition.countProperty(), 2L);
            assertThat(assembly.appBAssignmentProjection(config).project().apply(java.util.Map.of(), times))
                    .containsEntry(definition.countProperty(), 1L);
        });
    }

    @Test void incompleteInvalidAndUnknownPoolSettingsHaveNoDefaultsOrAliases() {
        runner.run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(prefix + "app-a-sim.window-millis=60000", prefix + "app-a-sim.max-count=10")
                .run(context -> assertThat(context).hasFailed());
        for (String field : java.util.List.of("window-millis", "max-count"))
            for (String invalid : java.util.List.of("0", "-1", "1.5", "many"))
                configured().withPropertyValues(prefix + "app-a-sim." + field + "=" + invalid)
                        .run(context -> assertThat(context).hasFailed());
        configured().withPropertyValues(prefix + "app-a-sim.max-assignments=10")
                .run(context -> assertThat(context).hasFailed());
        configured().withPropertyValues(prefix + "extra.window-millis=60000", prefix + "extra.max-count=10")
                .run(context -> assertThat(context).hasFailed());
    }
}
