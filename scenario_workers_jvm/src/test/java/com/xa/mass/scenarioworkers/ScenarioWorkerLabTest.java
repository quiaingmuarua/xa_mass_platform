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
import java.util.ArrayList;
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
    void missingGroupsInitializeOneFiftyLineInventoryPerGroup()
            throws Exception {
        Path root = labRoot();
        Files.createDirectories(root.resolve("unconfigured-data"));
        Files.writeString(
                root.resolve("unconfigured-data/preserved.txt"),
                "preserved",
                StandardCharsets.UTF_8
        );

        List<ScenarioWorkerLab.DiscoveredGroup> groups =
                new ScenarioWorkerLab(root.toString()).prepare(List.of(
                        group(PHONE_GROUP),
                        group(STRING_GROUP)
                ));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).workers()).hasSize(50);
        assertThat(groups.get(1).workers()).hasSize(50);
        assertThat(groups.get(0).workers())
                .extracting(ScenarioWorkerStateFile::labWorkerKey)
                .startsWith("workers-000.jsonl:1")
                .endsWith("workers-000.jsonl:50");
        assertThat(Files.readAllLines(
                root.resolve(PHONE_GROUP).resolve("workers-000.jsonl"),
                StandardCharsets.UTF_8
        )).hasSize(50);
        assertThat(groups.get(0).workers().get(49).workerProperties())
                .containsEntry(
                        "labInventoryKey",
                        "workers-000.jsonl"
                )
                .containsEntry("labInventoryLine", 50L)
                .containsEntry("labSlot", 50L)
                .containsEntry("convergenceSlot", "A");
        assertThat(root.resolve("unconfigured-data/preserved.txt"))
                .content().isEqualTo("preserved");
    }

    @Test
    void existingGroupDirectoriesPreserveFilesAndAllowZeroWorkers()
            throws Exception {
        Path root = labRoot();
        Files.createDirectories(root.resolve(PHONE_GROUP));
        Files.createDirectories(root.resolve(STRING_GROUP));
        Path custom = root.resolve(PHONE_GROUP).resolve("custom.jsonl");
        writeInventory(custom, List.of(
                Map.of("region", "first"),
                Map.of("region", "second")
        ));

        List<ScenarioWorkerLab.DiscoveredGroup> groups =
                new ScenarioWorkerLab(root.toString()).prepare(List.of(
                        group(PHONE_GROUP),
                        group(STRING_GROUP)
                ));

        assertThat(groups.get(0).workers())
                .extracting(ScenarioWorkerStateFile::labWorkerKey)
                .containsExactly("custom.jsonl:1", "custom.jsonl:2");
        assertThat(groups.get(1).workers()).isEmpty();
        assertThat(custom).content().contains("second");
    }

    @Test
    void deletingOneGroupRestoresOnlyThatGroupsDefaults()
            throws Exception {
        Path root = labRoot();
        Files.createDirectories(root.resolve(STRING_GROUP));
        Path preserved = root.resolve(STRING_GROUP).resolve("custom.jsonl");
        writeInventory(preserved, List.of(Map.of("region", "preserved")));

        List<ScenarioWorkerLab.DiscoveredGroup> groups =
                new ScenarioWorkerLab(root.toString()).prepare(
                        List.of(group(PHONE_GROUP), group(STRING_GROUP))
                );

        assertThat(groups.get(0).workers()).hasSize(50);
        assertThat(groups.get(1).workers())
                .extracting(ScenarioWorkerStateFile::labWorkerKey)
                .containsExactly("custom.jsonl:1");
        assertThat(preserved).content().contains("preserved");
    }

    @Test
    void malformedOversizedFileOrGroupFailsDiscovery() throws Exception {
        Path root = labRoot();
        Path group = root.resolve(PHONE_GROUP);
        Files.createDirectories(group);
        Files.writeString(
                group.resolve("broken.jsonl"),
                "not-json\n",
                StandardCharsets.UTF_8
        );
        assertInvalid(root);

        Files.delete(group.resolve("broken.jsonl"));
        List<Map<String, Object>> tooManyLines = new ArrayList<>();
        for (int index = 0;
             index <= ScenarioWorkerStateFile.MAX_RECORDS_PER_FILE;
             index++) {
            tooManyLines.add(Map.of("labSlot", index));
        }
        writeInventory(group.resolve("too-many.jsonl"), tooManyLines);
        assertInvalid(root);

        Files.delete(group.resolve("too-many.jsonl"));
        for (int file = 0; file < 100; file++) {
            writeInventory(
                    group.resolve("inventory-" + file + ".jsonl"),
                    properties(100)
            );
        }
        writeInventory(group.resolve("overflow.jsonl"), properties(1));
        assertThatThrownBy(() -> new ScenarioWorkerLab(
                root.toString()
        ).prepare(List.of(group(PHONE_GROUP))))
                .isInstanceOf(ScenarioWorkerAssemblyException.class)
                .hasMessageContaining("more than 10000");
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

    private void assertInvalid(Path root) {
        assertThatThrownBy(() -> new ScenarioWorkerLab(
                root.toString()
        ).prepare(List.of(group(PHONE_GROUP))))
                .isInstanceOf(ScenarioWorkerAssemblyException.class);
    }

    private static List<Map<String, Object>> properties(int count) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            values.add(Map.of("labSlot", index + 1));
        }
        return values;
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

    private static void writeInventory(
            Path path,
            List<Map<String, Object>> properties
    ) throws Exception {
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < properties.size(); index++) {
            Map<String, Object> complete = new java.util.LinkedHashMap<>();
            complete.put("labInventoryKey", path.getFileName().toString());
            complete.put("labInventoryLine", index + 1);
            complete.putAll(properties.get(index));
            lines.add(Jsons.toJson(Map.of(
                    "schemaVersion", 2,
                    "workerProperties", complete
            )));
        }
        Files.writeString(
                path,
                String.join("\n", lines) + "\n",
                StandardCharsets.UTF_8
        );
    }
}
