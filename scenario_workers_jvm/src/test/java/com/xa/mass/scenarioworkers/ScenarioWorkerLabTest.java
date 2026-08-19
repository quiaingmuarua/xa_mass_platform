package com.xa.mass.scenarioworkers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioWorkerLabTest {

    private static final String PHONE_GROUP =
            "scenario-phone-number-workers";
    private static final String STRING_GROUP =
            "scenario-string-utils-workers";

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void resolveTemporaryDirectory() throws IOException {
        temporaryDirectory = temporaryDirectory.toRealPath();
    }

    @Test
    void missingConfiguredGroupDirectoriesInitializeTwentyDefaults()
            throws Exception {
        Path root = labRoot();
        Files.createDirectories(root.resolve("legacy-worker"));
        Files.writeString(
                root.resolve("legacy-worker/identity.json"),
                "legacy",
                StandardCharsets.UTF_8
        );

        List<ScenarioWorkerLab.DiscoveredGroup> groups =
                new ScenarioWorkerLab(root.toString()).prepare(List.of(
                        group(PHONE_GROUP),
                        group(STRING_GROUP)
                ));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).workers()).hasSize(10);
        assertThat(groups.get(1).workers()).hasSize(10);
        assertThat(root.resolve("legacy-worker/identity.json"))
                .content().isEqualTo("legacy");
        assertThat(groups.get(0).workers().get(0).clientWorkerKey())
                .isEqualTo("scenario-phone-number-worker-001");
        assertThat(groups.get(0).workers().get(0).workerProperties())
                .containsEntry("labSlot", 1L);
    }

    @Test
    void existingGroupDirectoriesPreserveExactFilesAndAllowZeroWorkers()
            throws Exception {
        Path root = labRoot();
        Files.createDirectories(root.resolve(PHONE_GROUP));
        Files.createDirectories(root.resolve(STRING_GROUP));
        Files.createDirectories(root.resolve("unconfigured-group"));
        Path custom = root.resolve(PHONE_GROUP).resolve("custom.json");
        writeWorker(custom, Map.of("region", "edited"));
        writeWorker(
                root.resolve("unconfigured-group/ignored.json"),
                Map.of()
        );

        List<ScenarioWorkerLab.DiscoveredGroup> groups =
                new ScenarioWorkerLab(root.toString()).prepare(List.of(
                        group(PHONE_GROUP),
                        group(STRING_GROUP)
                ));

        assertThat(groups.get(0).workers())
                .extracting(ScenarioWorkerStateFile::clientWorkerKey)
                .containsExactly("custom");
        assertThat(groups.get(1).workers()).isEmpty();
        assertThat(custom).content().contains("edited");
        assertThat(root.resolve(PHONE_GROUP)
                .resolve("scenario-phone-number-worker-001.json"))
                .doesNotExist();
        assertThat(root.resolve("unconfigured-group/ignored.json"))
                .exists();
    }

    @Test
    void deletingOneGroupDirectoryRestoresOnlyThatGroupsDefaults()
            throws Exception {
        Path root = labRoot();
        Files.createDirectories(root.resolve(PHONE_GROUP));
        Files.createDirectories(root.resolve(STRING_GROUP));
        writeWorker(
                root.resolve(PHONE_GROUP).resolve("custom.json"),
                Map.of()
        );
        Path preserved = root.resolve(STRING_GROUP).resolve("custom.json");
        writeWorker(preserved, Map.of("region", "preserved"));
        Files.delete(root.resolve(PHONE_GROUP).resolve("custom.json"));
        Files.delete(root.resolve(PHONE_GROUP));

        List<ScenarioWorkerLab.DiscoveredGroup> groups =
                new ScenarioWorkerLab(root.toString()).prepare(
                        List.of(group(PHONE_GROUP), group(STRING_GROUP))
                );

        assertThat(groups.get(0).workers()).hasSize(10);
        assertThat(groups.get(1).workers())
                .extracting(ScenarioWorkerStateFile::clientWorkerKey)
                .containsExactly("custom");
        assertThat(preserved).content().contains("preserved");
        assertThat(root.resolve(STRING_GROUP)
                .resolve("scenario-string-utils-worker-001.json"))
                .doesNotExist();
    }

    @Test
    void malformedFileOrMoreThanOneHundredWorkersFailsDiscovery()
            throws Exception {
        Path root = labRoot();
        Path group = root.resolve(PHONE_GROUP);
        Files.createDirectories(group);
        Files.writeString(
                group.resolve("broken.json"),
                "not-json",
                StandardCharsets.UTF_8
        );

        assertThatThrownBy(() -> new ScenarioWorkerLab(
                root.toString()
        ).prepare(List.of(group(PHONE_GROUP))))
                .isInstanceOf(ScenarioWorkerAssemblyException.class);

        Files.delete(group.resolve("broken.json"));
        for (int index = 0;
             index <= ScenarioWorkerLab.MAX_WORKERS_PER_GROUP;
             index++) {
            writeWorker(
                    group.resolve("worker-%03d.json".formatted(index)),
                    Map.of()
            );
        }
        assertThatThrownBy(() -> new ScenarioWorkerLab(
                root.toString()
        ).prepare(List.of(group(PHONE_GROUP))))
                .isInstanceOf(ScenarioWorkerAssemblyException.class)
                .hasMessageContaining("more than 100");
    }

    @Test
    void refusesALabTargetOutsideTheDedicatedDataDirectory() {
        Path unsafe = temporaryDirectory.resolve("scenario-workers");

        assertThatThrownBy(() -> new ScenarioWorkerLab(
                unsafe.toString()
        ).prepare(List.of(group(PHONE_GROUP))))
                .isInstanceOf(ScenarioWorkerAssemblyException.class)
                .hasMessageContaining("data/scenario-workers");
    }

    private Path labRoot() {
        return temporaryDirectory.resolve("data/scenario-workers");
    }

    private static ScenarioWorkerGroupConfig group(String workerGroupId) {
        return new ScenarioWorkerGroupConfig(
                workerGroupId,
                List.of("event.one"),
                Duration.ofSeconds(1),
                TextMessageReconnectPolicy.defaults()
        );
    }

    private static void writeWorker(
            Path path,
            Map<String, Object> properties
    ) throws Exception {
        Files.writeString(
                path,
                Jsons.toJson(Map.of(
                        "schemaVersion",
                        1,
                        "workerProperties",
                        properties
                )),
                StandardCharsets.UTF_8
        );
    }
}
