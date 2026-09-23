package com.xa.mass.server.assembly.redis;

import static org.assertj.core.api.Assertions.assertThat;
import com.xa.mass.server.task.TaskItemOutcomeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.context.properties.bind.Binder;

class TaskItemOutcomeBindingTest {
    @Test void applicationBindingUsesThePureSharedDefaultsAndValidation() {
        var environment = new MockEnvironment().withProperty("xa.mass.task-item-outcomes.names.8", "read");
        var properties = Binder.get(environment).bind("xa.mass.task-item-outcomes", TaskItemOutcomeProperties.class).get();
        assertThat(properties.outcomeName(8)).isEqualTo("read");
        assertThat(properties.outcomeName(5)).isEqualTo("failed");
        assertThat(properties.outcomeName(6)).isEqualTo("succeeded");
        assertThat(Binder.get(new MockEnvironment()).bind("xa.mass.task-item-outcomes", TaskItemOutcomeProperties.class)
                .orElseGet(() -> new TaskItemOutcomeProperties(null)))
                .isEqualTo(new TaskItemOutcomeProperties(null));
    }
}
