package com.xa.mass.scenario.appchecks;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AppCheckScenarioConfigurationTest {
    @Test void sharedGroupUnionsOnlyTheChecksRequiredResources() {
        var original = AppCheckTestSettings.settings();
        var checks = new java.util.LinkedHashMap<>(original.checks());
        var first = original.requireCheck("app-a-registration");
        checks.put("same-event", first);
        var carrier = original.requireCheck("original-carrier");
        checks.put("shared-carrier", new AppCheckSettings.Check(carrier.label(), first.workerGroupId(), carrier.execution(), carrier.checkType(), carrier.parameters(), 1L));
        checks.put("second-window-event", new AppCheckSettings.Check("Other registration", first.workerGroupId(),
                new AppCheckSettings.Execution("extension.worker.other.registration", first.execution().refill(), first.execution().workerSelector(), 1),
                first.checkType(), first.parameters(), 1L));
        var settings = new AppCheckSettings(original.typeDefaults(), checks);
        var configuration = new AppCheckScenarioConfiguration();
        var snapshot = new AppCheckConfigurationStore.Snapshot(settings);
        var resources = configuration.appCheckRequirements(snapshot).requirementsByGroup().get("app-a-sim");
        assertThat(resources.eventCodes()).containsExactlyInAnyOrder("extension.worker.app.registration.check",
                "extension.worker.phonenumber.original-carrier.batch", "extension.worker.other.registration");
        assertThat(resources.pools()).containsExactlyInAnyOrder("any", "assignment-window");
        assertThat(resources.functions()).containsExactlyInAnyOrder("worker.any", "worker.assignment.available");
    }
}
