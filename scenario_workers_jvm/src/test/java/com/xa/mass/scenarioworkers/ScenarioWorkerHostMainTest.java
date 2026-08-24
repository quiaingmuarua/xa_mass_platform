package com.xa.mass.scenarioworkers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.json.Jsons;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScenarioWorkerHostMainTest {

    @Test
    void defaultsUseTheCheckedLocalLabCoordinates() {
        ScenarioWorkerHostMain.HostOptions options =
                ScenarioWorkerHostMain.HostOptions.parse(new String[0]);

        assertThat(options.runtimeApiBaseUrl())
                .isEqualTo(URI.create("http://127.0.0.1:18082"));
        assertThat(options.sandboxRoot())
                .isEqualTo("data/scenario-workers");
    }

    @Test
    void explicitCoordinatesAreAcceptedWithoutAdditionalConfiguration() {
        ScenarioWorkerHostMain.HostOptions options =
                ScenarioWorkerHostMain.HostOptions.parse(new String[]{
                        "--runtime-api-base-url=http://127.0.0.1:19082",
                        "--sandbox-root=C:/proof/data/scenario-workers"
                });

        assertThat(options.runtimeApiBaseUrl())
                .isEqualTo(URI.create("http://127.0.0.1:19082"));
        assertThat(options.sandboxRoot())
                .isEqualTo("C:/proof/data/scenario-workers");
    }

    @Test
    void unknownDuplicateOrInvalidArgumentsFailClosed() {
        assertThatThrownBy(() -> ScenarioWorkerHostMain.HostOptions.parse(
                new String[]{"--spring.profiles.active=scenario-workers"}
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown");
        assertThatThrownBy(() -> ScenarioWorkerHostMain.HostOptions.parse(
                new String[]{"--sandbox-root=one", "--sandbox-root=two"}
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> ScenarioWorkerHostMain.HostOptions.parse(
                new String[]{"--runtime-api-base-url=redis://127.0.0.1"}
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP(S)");
    }

    @Test
    void checkedAssemblyContainsOnlyTheTwoJvmCapabilityGroups() {
        Map<String, Object> assembly = Jsons.parseObject(
                ScenarioWorkerHostMain.loadDefaultCapabilityAssembly()
        );

        assertThat(assembly).containsOnlyKeys(
                "scenario-phone-number-workers",
                "scenario-string-utils-workers"
        );
        assertThat(assembly).doesNotContainKey("android-demo-workers");
    }
}
