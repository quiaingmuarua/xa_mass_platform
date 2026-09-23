package com.xa.mass.scenario.appchecks;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AppCheckAssignmentWindowTest {
    @Test void groupsProjectUsingTheirOwnInjectedWindowLength() {
        var configuration = new AppCheckScenarioConfiguration();
        var a = configuration.appAAssignmentProjection(100);
        var b = configuration.appBAssignmentProjection(200);
        assertThat(a.project().apply(Map.of(), List.of(99L, 100L)))
                .containsEntry("windowAssignmentCount", 1L);
        assertThat(b.project().apply(Map.of(), List.of(99L, 100L)))
                .containsEntry("windowAssignmentCount", 2L);
        assertThat(a.workerGroupId()).isEqualTo("app-a-sim");
        assertThat(b.workerGroupId()).isEqualTo("app-b-sim");
    }
    final AppCheckAssignmentWindow projection = new AppCheckAssignmentWindow(60_000);

    @Test void initializesAndCountsOnlyTheLatestObservedFixedWindow() {
        assertThat(projection.project(Map.of("unrelated", true), List.of(59_999L, 60_000L, 60_002L, 1L, 60_001L)))
                .isEqualTo(Map.of("lastAssignedAt", 60_002L, "windowAssignmentCount", 3L));
        var current = Map.<String, Object>of("lastAssignedAt", 60_050L, "windowAssignmentCount", 7, "unrelated", true);
        assertThat(projection.project(current, List.of(60_001L, 61_000L, 61_000L)))
                .isEqualTo(Map.of("lastAssignedAt", 61_000L, "windowAssignmentCount", 10L));
        assertThat(current).containsEntry("windowAssignmentCount", 7).containsEntry("unrelated", true);
        assertThat(projection.project(current, List.of(59_999L))).isEmpty();
        assertThat(projection.project(current, List.of(120_000L)))
                .isEqualTo(Map.of("lastAssignedAt", 120_000L, "windowAssignmentCount", 1L));
        assertThat(projection.project(current, List.of())).isEmpty();
    }

    @Test void persistedValuesResumeWithoutResetOrImplicitTimer() {
        var current = new AppCheckAssignmentWindow(60_000).project(Map.of(), List.of(60_010L));
        assertThat(new AppCheckAssignmentWindow(60_000).project(current, List.of(60_020L)))
                .isEqualTo(Map.of("lastAssignedAt", 60_020L, "windowAssignmentCount", 2L));
        assertThat(new AppCheckAssignmentWindow(100).project(Map.of(), List.of(99L, 100L, 101L)))
                .isEqualTo(Map.of("lastAssignedAt", 101L, "windowAssignmentCount", 2L));
    }

    @Test void malformedIncompleteNegativeAndOverflowStateCannotBeSilentlyRepaired() {
        for (var invalid : List.<Map<String, Object>>of(Map.of("lastAssignedAt", 1), Map.of("windowAssignmentCount", 1),
                Map.of("lastAssignedAt", "1", "windowAssignmentCount", 1),
                Map.of("lastAssignedAt", 1, "windowAssignmentCount", new BigDecimal("1.5")),
                Map.of("lastAssignedAt", -1, "windowAssignmentCount", 1),
                Map.of("lastAssignedAt", 1, "windowAssignmentCount", -1),
                Map.of("lastAssignedAt", 1, "windowAssignmentCount", Long.MAX_VALUE))) {
            assertThatThrownBy(() -> projection.project(invalid, List.of(2L))).isInstanceOf(RuntimeException.class);
        }
        var nullValue = new HashMap<String, Object>();
        nullValue.put("lastAssignedAt", null); nullValue.put("windowAssignmentCount", 1);
        assertThatThrownBy(() -> projection.project(nullValue, List.of(2L))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> projection.project(Map.of(), List.of(-1L))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AppCheckAssignmentWindow(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
