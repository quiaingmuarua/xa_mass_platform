package com.xa.mass.server.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskItemOutcomePropertiesTest {

    @Test
    void namesAreBoundAtApplicationStartupWithFixedFailedAndDefaultSuccess() {
        var outcomes = new TaskItemOutcomeProperties(Map.of(8, "read"));
        assertThat(outcomes.outcomeName(5)).isEqualTo("failed");
        assertThat(outcomes.outcomeName(6)).isEqualTo("succeeded");
        assertThat(outcomes.outcomeName(8)).isEqualTo("read");
        assertThat(outcomes.outcomeName(2)).isNull();
        assertThat(outcomes.outcomeName(9)).isNull();
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

}
