package com.xa.mass.scenarioworkers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioWorkerStateFileTest {

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void resolveTemporaryDirectory() throws IOException {
        temporaryDirectory = temporaryDirectory.toRealPath();
    }

    @Test
    void derivesStableKeysFromFilenameAndPhysicalLine() throws Exception {
        Path path = temporaryDirectory.resolve("workers-a.jsonl");
        writeLines(path, List.of(
                document(path, 1, Map.of("labSlot", "1")),
                document(path, 2, Map.of("labSlot", "2"))
        ));

        List<ScenarioWorkerStateFile> records =
                ScenarioWorkerStateFile.open(path);

        assertThat(records)
                .extracting(ScenarioWorkerStateFile::labWorkerKey)
                .containsExactly("workers-a.jsonl:1", "workers-a.jsonl:2");
        assertThat(records.get(1).inventoryFileName())
                .isEqualTo("workers-a.jsonl");
        assertThat(records.get(1).lineNumber()).isEqualTo(2);
        assertThat(records.get(1).workerProperties())
                .containsEntry("labSlot", "2");
    }

    @Test
    void rejectsBlankMultilineLegacyAndOversizedInventories()
            throws Exception {
        Path path = temporaryDirectory.resolve("workers.jsonl");
        writeLines(path, List.of(
                document(path, 1, Map.of()),
                "",
                document(path, 3, Map.of())
        ));
        assertInvalid(path);

        writeLines(path, List.of(
                "{\"schemaVersion\":2,",
                "\"workerProperties\":{}}"
        ));
        assertInvalid(path);

        writeLines(path, List.of(
                "{\"schemaVersion\":1,\"workerProperties\":{}}"
        ));
        assertInvalid(path);

        List<String> oversized = new ArrayList<>();
        for (int index = 0;
             index <= ScenarioWorkerStateFile.MAX_RECORDS_PER_FILE;
             index++) {
            oversized.add(document(path, index + 1, Map.of(
                    "labSlot", Integer.toString(index)
            )));
        }
        writeLines(path, oversized);
        assertInvalid(path);
    }

    @Test
    void reloadsPropertiesAndAtomicallyReplacesOnlyTheTargetLine()
            throws Exception {
        Path path = temporaryDirectory.resolve("workers.jsonl");
        String first = document(path, 1, Map.of("region", "first"));
        String second = document(path, 2, Map.of("region", "second"));
        writeLines(path, List.of(first, second));
        ScenarioWorkerStateFile target =
                ScenarioWorkerStateFile.open(path).get(0);

        writeLines(path, List.of(
                document(path, 1, Map.of("region", "external")),
                second
        ));
        assertThat(target.workerProperties())
                .containsEntry("region", "external");

        target.replace(document(path, 1, Map.of(
                "region", "replacement"
        )));

        List<String> updated = Files.readAllLines(
                path,
                StandardCharsets.UTF_8
        );
        assertThat(updated).hasSize(2);
        assertThat(Jsons.parseObject(updated.get(0)))
                .containsEntry(
                        "workerProperties",
                        Map.of(
                                "labInventoryKey", "workers.jsonl",
                                "labInventoryLine", "1",
                                "region", "replacement"
                        )
                );
        assertThat(updated.get(1)).isEqualTo(second);
        try (var files = Files.list(temporaryDirectory)) {
            assertThat(files.map(item -> item.getFileName().toString()))
                    .containsExactly("workers.jsonl");
        }
    }

    @Test
    void invalidReplacementOrCorruptSiblingDoesNotModifyTheFile()
            throws Exception {
        Path path = temporaryDirectory.resolve("workers.jsonl");
        writeLines(path, List.of(
                document(path, 1, Map.of("region", "first")),
                document(path, 2, Map.of("region", "second"))
        ));
        ScenarioWorkerStateFile target =
                ScenarioWorkerStateFile.open(path).get(0);
        String original = Files.readString(path, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> target.replace(
                "{\"schemaVersion\":2,\"extra\":true}"
        )).isInstanceOf(ScenarioWorkerAssemblyException.class);
        assertThat(Files.readString(path, StandardCharsets.UTF_8))
                .isEqualTo(original);

        writeLines(path, List.of(document(path, 1, Map.of()), "broken"));
        String corrupt = Files.readString(path, StandardCharsets.UTF_8);
        assertThatThrownBy(() -> target.replace(document(path, 1, Map.of())))
                .isInstanceOf(ScenarioWorkerAssemblyException.class);
        assertThat(Files.readString(path, StandardCharsets.UTF_8))
                .isEqualTo(corrupt);
    }

    private void assertInvalid(Path path) {
        assertThatThrownBy(() -> ScenarioWorkerStateFile.open(path))
                .isInstanceOf(ScenarioWorkerAssemblyException.class)
                .extracting("errorCode")
                .isEqualTo(14013);
    }

    private static String document(
            Path path,
            int lineNumber,
            Map<String, Object> properties
    ) {
        var complete = new java.util.LinkedHashMap<String, Object>();
        complete.put("labInventoryKey", path.getFileName().toString());
        complete.put("labInventoryLine", Integer.toString(lineNumber));
        complete.putAll(properties);
        return Jsons.toJson(Map.of(
                "schemaVersion", 2,
                "workerProperties", complete
        ));
    }

    private static void writeLines(Path path, List<String> lines)
            throws Exception {
        Files.writeString(
                path,
                String.join("\n", lines) + "\n",
                StandardCharsets.UTF_8
        );
    }
}
