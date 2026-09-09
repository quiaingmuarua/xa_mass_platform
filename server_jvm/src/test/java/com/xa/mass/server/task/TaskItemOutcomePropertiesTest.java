package com.xa.mass.server.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class TaskItemOutcomePropertiesTest {

    @Test
    void namesAreBoundAtApplicationStartupWithFixedFailedAndDefaultSuccess() {
        new ApplicationContextRunner().withUserConfiguration(Config.class)
                .withPropertyValues("xa.mass.task-item-outcomes.names.8=read")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var outcomes = context.getBean(TaskItemOutcomeProperties.class);
                    assertThat(outcomes.outcomeName(5)).isEqualTo("failed");
                    assertThat(outcomes.outcomeName(6)).isEqualTo("succeeded");
                    assertThat(outcomes.outcomeName(8)).isEqualTo("read");
                    assertThat(outcomes.outcomeName(2)).isNull();
                    assertThat(outcomes.outcomeName(9)).isNull();
                });
        assertThat(new TaskItemOutcomeProperties(Map.of(6, "sent")).outcomeName(6)).isEqualTo("sent");
    }

    @Test
    void invalidOrDuplicateNamesFailConfiguration() {
        for (var names : java.util.List.of(Map.of(5, "error"), Map.of(2, "custom"), Map.of(10, "other"),
                Map.of(6, " "), Map.of(7, "succeeded"), Map.of(6, "failed"), Map.of(8, "read", 9, "read"))) {
            assertThatThrownBy(() -> new TaskItemOutcomeProperties(names))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TaskItemOutcomeProperties.class)
    static class Config {
    }
}
