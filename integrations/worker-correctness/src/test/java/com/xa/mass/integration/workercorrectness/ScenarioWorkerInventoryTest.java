package com.xa.mass.integration.workercorrectness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioWorkerInventoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void validatesSchemaV2LabWithoutPersistedWorkerIdentity()
            throws Exception {
        CorrectnessSpec spec = spec();
        createGroup("group-a", 2);

        ScenarioWorkerInventory.validateLab(temporaryDirectory, spec);
    }

    @Test
    void rejectsMissingExtraAndInvalidLabState() throws Exception {
        CorrectnessSpec spec = spec();
        createGroup("group-a", 2);
        Files.writeString(
                temporaryDirectory.resolve("group-a/extra.jsonl"),
                state("extra.jsonl", 1) + "\n",
                StandardCharsets.UTF_8
        );
        ScenarioWorkerInventory.InventoryMismatch extra = assertThrows(
                ScenarioWorkerInventory.InventoryMismatch.class,
                () -> ScenarioWorkerInventory.validateLab(
                        temporaryDirectory,
                        spec
                )
        );
        assertEquals(List.of("extra.jsonl:1"), extra.unexpectedIds());

        Files.delete(temporaryDirectory.resolve("group-a/extra.jsonl"));
        createGroup("group-a", 1);
        ScenarioWorkerInventory.InventoryMismatch missing = assertThrows(
                ScenarioWorkerInventory.InventoryMismatch.class,
                () -> ScenarioWorkerInventory.validateLab(
                        temporaryDirectory,
                        spec
                )
        );
        assertEquals(List.of("workers.jsonl:2"), missing.missingIds());

        Files.writeString(
                temporaryDirectory.resolve("group-a/workers.jsonl"),
                state("workers.jsonl", 1) + "\n" + Jsons.toJson(Map.of(
                        "schemaVersion", 1,
                        "workerId", UUID.randomUUID().toString(),
                        "workerProperties", Map.of()
                )) + "\n",
                StandardCharsets.UTF_8
        );
        ScenarioWorkerInventory.InventoryMismatch invalid = assertThrows(
                ScenarioWorkerInventory.InventoryMismatch.class,
                () -> ScenarioWorkerInventory.validateLab(
                        temporaryDirectory,
                        spec
                )
        );
        assertEquals(List.of("workers.jsonl:2"), invalid.inconsistentIds());
    }

    @Test
    void rejectsWrongLabGroup() throws Exception {
        CorrectnessSpec spec = spec();
        createGroup("wrong-group", 2);

        ScenarioWorkerInventory.InventoryMismatch mismatch = assertThrows(
                ScenarioWorkerInventory.InventoryMismatch.class,
                () -> ScenarioWorkerInventory.validateLab(
                        temporaryDirectory,
                        spec
                )
        );

        assertEquals(List.of("group-a"), mismatch.missingIds());
        assertEquals(List.of("wrong-group"), mismatch.unexpectedIds());
    }

    @Test
    void mapsRuntimePreviewAndRequiresCanonicalUniqueIdentities() {
        CorrectnessSpec spec = spec();
        String workerOne = UUID.randomUUID().toString();
        String workerTwo = UUID.randomUUID().toString();

        Map<String, Map<String, String>> inventory =
                ScenarioWorkerInventory.loadRuntime(
                        spec,
                        ignored -> Map.of(
                                "workers.jsonl:1", workerOne,
                                "workers.jsonl:2", workerTwo,
                                "unrelated-worker", UUID.randomUUID().toString()
                        )
                );

        assertEquals(
                Map.of(
                        "workers.jsonl:1", workerOne,
                        "workers.jsonl:2", workerTwo
                ),
                inventory.get("group-a")
        );

        ScenarioWorkerInventory.InventoryMismatch missing = assertThrows(
                ScenarioWorkerInventory.InventoryMismatch.class,
                () -> ScenarioWorkerInventory.loadRuntime(
                        spec,
                        ignored -> Map.of("workers.jsonl:1", workerOne)
                )
        );
        assertEquals(List.of("workers.jsonl:2"), missing.missingIds());

        ScenarioWorkerInventory.InventoryMismatch duplicate = assertThrows(
                ScenarioWorkerInventory.InventoryMismatch.class,
                () -> ScenarioWorkerInventory.loadRuntime(
                        spec,
                        ignored -> Map.of(
                                "workers.jsonl:1", workerOne,
                                "workers.jsonl:2", workerOne
                        )
                )
        );
        assertEquals(List.of(workerOne), duplicate.inconsistentIds());

        ScenarioWorkerInventory.InventoryMismatch invalid = assertThrows(
                ScenarioWorkerInventory.InventoryMismatch.class,
                () -> ScenarioWorkerInventory.loadRuntime(
                        spec,
                        ignored -> Map.of(
                                "workers.jsonl:1", workerOne,
                                "workers.jsonl:2", "not-a-worker-id"
                        )
                )
        );
        assertEquals(List.of("workers.jsonl:2"), invalid.inconsistentIds());
    }

    private CorrectnessSpec spec() {
        return new CorrectnessSpec(
                "adapter",
                Map.of("group-a", List.of(
                        "workers.jsonl:1",
                        "workers.jsonl:2"
                ))
        );
    }

    private void createGroup(String groupId, int count)
            throws Exception {
        Path group = Files.createDirectories(
                temporaryDirectory.resolve(groupId)
        );
        List<String> records = new java.util.ArrayList<>();
        for (int line = 1; line <= count; line++) {
            records.add(state("workers.jsonl", line));
        }
        Files.writeString(
                group.resolve("workers.jsonl"),
                String.join("\n", records) + "\n",
                StandardCharsets.UTF_8
        );
    }

    private static String state(String inventoryKey, int inventoryLine) {
        return Jsons.toJson(Map.of(
                "schemaVersion", 2,
                "workerProperties", Map.of(
                        "labInventoryKey", inventoryKey,
                        "labInventoryLine", Integer.toString(inventoryLine),
                        "dynamic", UUID.randomUUID().toString()
                )
        ));
    }
}
