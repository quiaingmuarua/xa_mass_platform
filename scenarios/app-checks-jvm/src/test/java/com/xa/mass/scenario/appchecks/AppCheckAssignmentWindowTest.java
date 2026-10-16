package com.xa.mass.scenario.appchecks;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AppCheckAssignmentWindowTest {
    @Test void sharedGroupUnionsResourcesAndProjectsEachWindowEventOnce() {
        var original = AppCheckTestSettings.settings();
        var checks = new java.util.LinkedHashMap<>(original.checks());
        var first = original.requireCheck("app-a-registration");
        checks.put("same-event", first);
        var carrier = original.requireCheck("original-carrier");
        checks.put("shared-carrier", new AppCheckSettings.Check(carrier.label(), first.workerGroupId(), carrier.execution(), carrier.checkType(), carrier.parameters()));
        checks.put("second-window-event", new AppCheckSettings.Check("Other registration", first.workerGroupId(),
                new AppCheckSettings.Execution("extension.worker.other.registration", first.execution().refill(), first.execution().workerSelector()),
                first.checkType(), first.parameters()));
        var settings = new AppCheckSettings(original.typeDefaults(), checks, original.poolGroups());
        var configuration = new AppCheckScenarioConfiguration();
        var snapshot = new AppCheckConfigurationStore.Snapshot(settings);
        var resources = configuration.appCheckRequirements(snapshot).requirementsByGroup().get("app-a-sim");
        assertThat(resources.eventCodes()).containsExactlyInAnyOrder("extension.worker.app.registration.check",
                "extension.worker.phonenumber.original-carrier", "extension.worker.other.registration");
        assertThat(resources.pools()).containsExactlyInAnyOrder("any", "assignment-window");
        assertThat(resources.functions()).containsExactlyInAnyOrder("worker.any", "worker.assignment.available");
        var selections = configuration.appCheckAssignmentProjections(snapshot).projectionsByGroup().get("app-a-sim");
        assertThat(selections).extracting(com.xa.mass.server.worker.observation.WorkerPropertyProjection.Projection::messageEventName)
                .containsExactlyInAnyOrder("extension.worker.app.registration.check", "extension.worker.other.registration");
        var current = new java.util.HashMap<String,Object>();
        selections.forEach(selection -> current.putAll(selection.project().apply(current, List.of(100L))));
        assertThat(current).containsEntry("windowAssignmentCount", 2L);
    }
    @Test void groupsProjectUsingTheirOwnInjectedWindowLength() {
        var configuration = new AppCheckScenarioConfiguration();
        var seed = AppCheckTestSettings.settings();
        var settings = new AppCheckSettings(seed.typeDefaults(), seed.checks(), Map.of(
                "app-a-sim", new com.xa.mass.workermatching.FixedWindowPoolDefinition.WindowLimit(100, 10),
                "app-b-sim", new com.xa.mass.workermatching.FixedWindowPoolDefinition.WindowLimit(200, 20)));
        var config = new AppCheckConfigurationStore.Snapshot(settings);
        var projections = configuration.appCheckAssignmentProjections(config).projectionsByGroup();
        assertThat(configuration.appCheckWindowPool(config).limitsByGroup()).isEqualTo(settings.poolGroups());
        assertThat(projections.get("app-a-sim").getFirst().project().apply(Map.of(), List.of(99L, 100L)))
                .containsEntry("windowAssignmentCount", 1L);
        assertThat(projections.get("app-b-sim").getFirst().project().apply(Map.of(), List.of(99L, 100L)))
                .containsEntry("windowAssignmentCount", 2L);
        assertThat(projections.keySet()).containsExactly("app-a-sim", "app-b-sim");
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
