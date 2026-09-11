package com.xa.mass.workersimulator;

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

class WorkerSimulatorLabTest {

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

        List<WorkerSimulatorLab.DiscoveredGroup> groups =
                prepare(root, List.of(
                        group(PHONE_GROUP),
                        group(STRING_GROUP)
                ));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).workers()).hasSize(50);
        assertThat(groups.get(1).workers()).hasSize(50);
        assertThat(groups.get(0).workers())
                .extracting(WorkerSimulatorStateFile::labWorkerKey)
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
                .containsEntry("labInventoryLine", "50")
                .containsEntry("labSlot", "50")
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

        List<WorkerSimulatorLab.DiscoveredGroup> groups =
                prepare(root, List.of(
                        group(PHONE_GROUP),
                        group(STRING_GROUP)
                ));

        assertThat(groups.get(0).workers())
                .extracting(WorkerSimulatorStateFile::labWorkerKey)
                .containsExactly("custom.jsonl:1", "custom.jsonl:2");
        assertThat(groups.get(1).workers()).isEmpty();
        assertThat(custom).content().contains("second");
    }

    @Test
    void missingGroupInitializesWithoutChangingExistingGroup()
            throws Exception {
        Path root = labRoot();
        Files.createDirectories(root.resolve(STRING_GROUP));
        Path preserved = root.resolve(STRING_GROUP).resolve("custom.jsonl");
        writeInventory(preserved, List.of(Map.of("region", "preserved")));

        List<WorkerSimulatorLab.DiscoveredGroup> groups =
                prepare(root,
                        List.of(group(PHONE_GROUP), group(STRING_GROUP))
                );

        assertThat(groups.get(0).workers()).hasSize(50);
        assertThat(groups.get(1).workers())
                .extracting(WorkerSimulatorStateFile::labWorkerKey)
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
             index <= WorkerSimulatorStateFile.MAX_RECORDS_PER_FILE;
             index++) {
            tooManyLines.add(Map.of("labSlot", index));
        }
        writeInventory(group.resolve("too-many.jsonl"), tooManyLines);
        assertInvalid(root);

        Files.delete(group.resolve("too-many.jsonl"));
        for (int file = 0; file < 150; file++) {
            writeInventory(
                    group.resolve("inventory-" + file + ".jsonl"),
                    properties(100)
            );
        }
        writeInventory(group.resolve("overflow.jsonl"), properties(1));
        assertThatThrownBy(() -> prepare(root, List.of(group(PHONE_GROUP))))
                .isInstanceOf(WorkerSimulatorAssemblyException.class)
                .hasMessageContaining("more than 15000");
    }

    @Test
    void refusesALabTargetOutsideTheDedicatedDataDirectory() {
        Path unsafe = temporaryDirectory.resolve("scenario-workers");

        assertThatThrownBy(() -> prepare(unsafe, List.of(group(PHONE_GROUP))))
                .isInstanceOf(WorkerSimulatorAssemblyException.class)
                .hasMessageContaining("data/scenario-workers");
    }

    @Test void generationCrossesFilesAndReuseIgnoresChangedTemplates() throws Exception {
        var first = new WorkerSimulatorGroupConfig("g", List.of("test"), 101,
                Map.of("ordinal", Map.of("$index", List.of(1, 1))), false,
                Duration.ofSeconds(1), TextMessageReconnectPolicy.defaults());
        var generated = prepare(labRoot(), List.of(first)).get(0).workers();
        assertThat(generated).hasSize(101);
        assertThat(generated.get(100).labWorkerKey()).isEqualTo("workers-001.jsonl:1");
        assertThat(generated.get(100).workerProperties()).containsEntry("ordinal", "101");
        var changed = new WorkerSimulatorGroupConfig("g", List.of("test"), 1, Map.of("ordinal", "different"),
                false, Duration.ofSeconds(1), TextMessageReconnectPolicy.defaults());
        assertThat(prepare(labRoot(), List.of(changed)).get(0).workers()).hasSize(101);
    }

    @Test void resetIsGroupLocalAndValidationHappensBeforeAnyReplacement() throws Exception {
        prepare(labRoot(), List.of(group(PHONE_GROUP), group(STRING_GROUP)));
        Path preserved = labRoot().resolve(STRING_GROUP + "/workers-000.jsonl");
        String before = Files.readString(preserved);
        var reset = new WorkerSimulatorGroupConfig(PHONE_GROUP, List.of("test"), 1, Map.of("new", "yes"),
                true, Duration.ofSeconds(1), TextMessageReconnectPolicy.defaults());
        var lab = new WorkerSimulatorLab(labRoot().toString());
        assertThatThrownBy(() -> lab.prepare(List.of(reset, group(STRING_GROUP)), 0,
                ignored -> { throw new IllegalArgumentException("invalid world"); })).hasMessage("invalid world");
        assertThat(Files.readAllLines(labRoot().resolve(PHONE_GROUP + "/workers-000.jsonl"))).hasSize(50);
        var result = lab.prepare(List.of(reset, group(STRING_GROUP)), 0, ignored -> {});
        assertThat(result.get(0).workers()).hasSize(1);
        assertThat(result.get(0).workers().get(0).workerProperties()).containsEntry("new", "yes");
        assertThat(Files.readString(preserved)).isEqualTo(before);
    }

    @Test void failedDirectoryInstallRestoresTheOldInventory() throws Exception {
        prepare(labRoot(), List.of(group(PHONE_GROUP)));
        Path target = labRoot().resolve(PHONE_GROUP);
        String old = Files.readString(target.resolve("workers-000.jsonl"));
        var lab = org.mockito.Mockito.spy(new WorkerSimulatorLab(labRoot().toString()));
        org.mockito.Mockito.doAnswer(call -> {
            Path source = call.getArgument(0);
            if (source.getFileName().toString().startsWith(".initializing-")) throw new IOException("install failed");
            return call.callRealMethod();
        }).when(lab).moveDirectory(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(target));
        var reset = new WorkerSimulatorGroupConfig(PHONE_GROUP, List.of("test"), 0, Map.of(),
                true, Duration.ofSeconds(1), TextMessageReconnectPolicy.defaults());
        assertThatThrownBy(() -> lab.prepare(List.of(reset), 0, ignored -> {}))
                .isInstanceOf(WorkerSimulatorAssemblyException.class);
        assertThat(Files.readString(target.resolve("workers-000.jsonl"))).isEqualTo(old);
        try (var paths = Files.list(labRoot())) { assertThat(paths.map(Path::getFileName).toList()).containsExactly(Path.of(PHONE_GROUP)); }
    }


    @Test
    void businessValidationRejectsRebuildBeforeReplacingRetainedInventory() throws Exception {
        prepare(labRoot(), List.of(group(PHONE_GROUP)));
        Path existing = labRoot().resolve(PHONE_GROUP + "/workers-000.jsonl");
        String before = Files.readString(existing);
        var invalid = new WorkerSimulatorGroupConfig(PHONE_GROUP,
                List.of(com.xa.mass.workersimulator.sms.SmsScenario.START_EVENT), 2,
                Map.of("phone", "861700000001", "country", "CN"), true,
                Duration.ofSeconds(1), TextMessageReconnectPolicy.defaults());
        var config = new WorkerSimulatorConfig(java.net.URI.create("http://127.0.0.1:1"),
                labRoot(), 0, 0, List.of(invalid), WorkerSimulatorStartupPlan.defaults());
        try (var host = WorkerSimulator.create(config)) {
            assertThatThrownBy(host::start)
                    .hasRootCauseMessage("Duplicate inventory phone");
        }
        assertThat(Files.readString(existing)).isEqualTo(before);
        try (var children = Files.list(labRoot())) {
            assertThat(children.map(Path::getFileName).toList()).containsExactly(Path.of(PHONE_GROUP));
        }
    }

    @Test
    void templateOverflowDoesNotReplaceExistingInventory() throws Exception {
        prepare(labRoot(), List.of(group(PHONE_GROUP)));
        Path existing = labRoot().resolve(PHONE_GROUP + "/workers-000.jsonl");
        String before = Files.readString(existing);
        var invalid = new WorkerSimulatorGroupConfig(PHONE_GROUP, List.of("test"), 2,
                Map.of("value", Map.of("$index", List.of(Long.MAX_VALUE, 1))), true,
                Duration.ofSeconds(1), TextMessageReconnectPolicy.defaults());
        assertThatThrownBy(() -> prepare(labRoot(), List.of(invalid)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(existing)).isEqualTo(before);
    }

    @Test
    void groupDirectoryAliasesAreRejectedOnCaseInsensitivePaths() {
        org.junit.jupiter.api.Assumptions.assumeTrue(Path.of("A").equals(Path.of("a")));
        assertThatThrownBy(() -> prepare(labRoot(), List.of(group("A"), group("a"))))
                .hasMessageContaining("distinct inventory directories");
        assertThat(labRoot()).doesNotExist();
    }

    private void assertInvalid(Path root) {
        assertThatThrownBy(() -> prepare(root, List.of(group(PHONE_GROUP))))
                .isInstanceOf(WorkerSimulatorAssemblyException.class);
    }

    private static List<Map<String, Object>> properties(int count) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            values.add(Map.of("labSlot", Integer.toString(index + 1)));
        }
        return values;
    }

    private Path labRoot() {
        return temporaryDirectory.resolve("data/scenario-workers");
    }

    private static List<WorkerSimulatorLab.DiscoveredGroup> prepare(Path root, List<WorkerSimulatorGroupConfig> groups) {
        return new WorkerSimulatorLab(root.toString()).prepare(groups, 0, ignored -> {});
    }

    private static WorkerSimulatorGroupConfig group(String workerGroupId) {
        return new WorkerSimulatorGroupConfig(
                workerGroupId,
                List.of("event.one"), 50,
                Map.of("labSlot", Map.of("$index", List.of(1, 1)), "convergenceSlot", "A"), false,
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
            complete.put("labInventoryLine", Integer.toString(index + 1));
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
