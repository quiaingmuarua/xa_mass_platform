package com.xa.mass.integration.workerfleet;

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
        FleetSpec spec = spec();
        createGroup("group-a", List.of("client-1", "client-2"));
        Files.writeString(
                temporaryDirectory.resolve("group-a/client-2.json"),
                "{\"schemaVersion\":2}",
                StandardCharsets.UTF_8
        );

        ScenarioWorkerInventory.validateLab(temporaryDirectory, spec);
    }

    @Test
    void rejectsMissingExtraAndInvalidLabState() throws Exception {
        FleetSpec spec = spec();
        createGroup("group-a", List.of("client-1", "client-2"));
        Files.writeString(
                temporaryDirectory.resolve("group-a/extra.json"),
                state(),
                StandardCharsets.UTF_8
        );
        ScenarioWorkerInventory.InventoryMismatch extra = assertThrows(
                ScenarioWorkerInventory.InventoryMismatch.class,
                () -> ScenarioWorkerInventory.validateLab(
                        temporaryDirectory,
                        spec
                )
        );
        assertEquals(List.of("extra"), extra.unexpectedIds());

        Files.delete(temporaryDirectory.resolve("group-a/extra.json"));
        Files.delete(temporaryDirectory.resolve("group-a/client-2.json"));
        ScenarioWorkerInventory.InventoryMismatch missing = assertThrows(
                ScenarioWorkerInventory.InventoryMismatch.class,
                () -> ScenarioWorkerInventory.validateLab(
                        temporaryDirectory,
                        spec
                )
        );
        assertEquals(List.of("client-2"), missing.missingIds());

        Files.writeString(
                temporaryDirectory.resolve("group-a/client-2.json"),
                Jsons.toJson(Map.of(
                        "schemaVersion", 1,
                        "workerId", UUID.randomUUID().toString(),
                        "workerProperties", Map.of()
                )),
                StandardCharsets.UTF_8
        );
        ScenarioWorkerInventory.InventoryMismatch invalid = assertThrows(
                ScenarioWorkerInventory.InventoryMismatch.class,
                () -> ScenarioWorkerInventory.validateLab(
                        temporaryDirectory,
                        spec
                )
        );
        assertEquals(List.of("client-2"), invalid.inconsistentIds());
    }

    @Test
    void rejectsWrongLabGroup() throws Exception {
        FleetSpec spec = spec();
        createGroup("wrong-group", List.of("client-1", "client-2"));

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
        FleetSpec spec = spec();
        String workerOne = UUID.randomUUID().toString();
        String workerTwo = UUID.randomUUID().toString();

        Map<String, Map<String, String>> inventory =
                ScenarioWorkerInventory.loadRuntime(
                        spec,
                        ignored -> Map.of(
                                "client-1", workerOne,
                                "client-2", workerTwo,
                                "unrelated-worker", UUID.randomUUID().toString()
                        )
                );

        assertEquals(
                Map.of("client-1", workerOne, "client-2", workerTwo),
                inventory.get("group-a")
        );

        ScenarioWorkerInventory.InventoryMismatch missing = assertThrows(
                ScenarioWorkerInventory.InventoryMismatch.class,
                () -> ScenarioWorkerInventory.loadRuntime(
                        spec,
                        ignored -> Map.of("client-1", workerOne)
                )
        );
        assertEquals(List.of("client-2"), missing.missingIds());

        ScenarioWorkerInventory.InventoryMismatch duplicate = assertThrows(
                ScenarioWorkerInventory.InventoryMismatch.class,
                () -> ScenarioWorkerInventory.loadRuntime(
                        spec,
                        ignored -> Map.of(
                                "client-1", workerOne,
                                "client-2", workerOne
                        )
                )
        );
        assertEquals(List.of(workerOne), duplicate.inconsistentIds());

        ScenarioWorkerInventory.InventoryMismatch invalid = assertThrows(
                ScenarioWorkerInventory.InventoryMismatch.class,
                () -> ScenarioWorkerInventory.loadRuntime(
                        spec,
                        ignored -> Map.of(
                                "client-1", workerOne,
                                "client-2", "not-a-worker-id"
                        )
                )
        );
        assertEquals(List.of("client-2"), invalid.inconsistentIds());
    }

    private FleetSpec spec() {
        return new FleetSpec(
                "adapter",
                Map.of("group-a", List.of("client-1", "client-2"))
        );
    }

    private void createGroup(String groupId, List<String> clientKeys)
            throws Exception {
        Path group = Files.createDirectories(
                temporaryDirectory.resolve(groupId)
        );
        for (String clientKey : clientKeys) {
            Files.writeString(
                    group.resolve(clientKey + ".json"),
                    state(),
                    StandardCharsets.UTF_8
            );
        }
    }

    private static String state() {
        return Jsons.toJson(Map.of(
                "schemaVersion", 2,
                "workerProperties", Map.of(
                        "dynamic",
                        UUID.randomUUID().toString()
                )
        ));
    }
}
