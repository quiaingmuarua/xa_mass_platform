package com.xa.mass.scenarioworkers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.json.Jsons;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioWorkerHostMainTest {

    @Test
    void defaultsUseTheCheckedLocalLabCoordinates() {
        ScenarioWorkerHostMain.HostOptions options =
                ScenarioWorkerHostMain.HostOptions.parse(new String[0]);

        assertThat(options.runtimeApiBaseUrl())
                .isEqualTo(URI.create("http://127.0.0.1:18082"));
        assertThat(options.sandboxRoot())
                .isEqualTo("data/scenario-workers");
        assertThat(options.controlPort()).isEqualTo(18086);
        assertThat(options.startupPlanPath()).isNull();
        assertThat(options.capabilityAssemblyPath()).isNull();
    }

    @Test
    void explicitCoordinatesAreAcceptedWithoutAdditionalConfiguration() {
        ScenarioWorkerHostMain.HostOptions options =
                ScenarioWorkerHostMain.HostOptions.parse(new String[]{
                        "--runtime-api-base-url=http://127.0.0.1:19082",
                        "--sandbox-root=C:/proof/data/scenario-workers",
                        "--control-port=0",
                        "--startup-plan=C:/proof/startup-plan.json",
                        "--capability-assembly=C:/proof/capabilities.json"
                });

        assertThat(options.runtimeApiBaseUrl())
                .isEqualTo(URI.create("http://127.0.0.1:19082"));
        assertThat(options.sandboxRoot())
                .isEqualTo("C:/proof/data/scenario-workers");
        assertThat(options.controlPort()).isZero();
        assertThat(options.startupPlanPath())
                .isEqualTo("C:/proof/startup-plan.json");
        assertThat(options.capabilityAssemblyPath())
                .isEqualTo("C:/proof/capabilities.json");
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
        assertThatThrownBy(() -> ScenarioWorkerHostMain.HostOptions.parse(
                new String[]{"--control-port=65536"}
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control-port");
        assertThatThrownBy(() -> ScenarioWorkerHostMain.HostOptions.parse(
                new String[]{"--initial-workers=none"}
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown");
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

    @Test
    void explicitCapabilityAssemblyIsReadFromTheConfiguredFile(
            @TempDir Path directory
    ) throws Exception {
        Path assembly = directory.resolve("capabilities.json");
        Files.writeString(assembly, "{\"scenario-string-utils-workers\":{"
                + "\"eventCodes\":[\"extension.worker.string.md5\"]}}"
        );

        assertThat(ScenarioWorkerHostMain.loadCapabilityAssembly(
                assembly.toString()
        )).contains("scenario-string-utils-workers");
        assertThatThrownBy(() -> ScenarioWorkerHostMain
                .loadCapabilityAssembly(directory.resolve("missing.json")
                        .toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("regular file");
    }
}
