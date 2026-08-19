package com.xa.mass.integration.workerfleet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioWorkerInventoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsExactConfiguredInventory() throws Exception {
        FleetSpec spec = spec();
        Map<String, String> expected = createGroup(
                "group-a",
                List.of("client-1", "client-2")
        );

        Map<String, Map<String, String>> inventory =
                ScenarioWorkerInventory.load(temporaryDirectory, spec);

        assertEquals(expected, inventory.get("group-a"));
    }

    @Test
    void rejectsMissingExtraAndDuplicateIdentities() throws Exception {
        FleetSpec spec = spec();
        Map<String, String> workers = createGroup(
                "group-a",
                List.of("client-1", "client-2")
        );
        Files.writeString(
                temporaryDirectory.resolve("group-a").resolve("extra.json"),
                state(UUID.randomUUID().toString()),
                StandardCharsets.UTF_8
        );
        ScenarioWorkerInventory.InventoryMismatch extra = assertThrows(
                ScenarioWorkerInventory.InventoryMismatch.class,
                () -> ScenarioWorkerInventory.load(temporaryDirectory, spec)
        );
        assertEquals(List.of("extra"), extra.unexpectedIds());

        Files.delete(temporaryDirectory.resolve("group-a").resolve(
                "extra.json"
        ));
        Files.delete(temporaryDirectory.resolve("group-a").resolve(
                "client-2.json"
        ));
        ScenarioWorkerInventory.InventoryMismatch missing = assertThrows(
                ScenarioWorkerInventory.InventoryMismatch.class,
                () -> ScenarioWorkerInventory.load(temporaryDirectory, spec)
        );
        assertEquals(List.of("client-2"), missing.missingIds());

        Files.writeString(
                temporaryDirectory.resolve("group-a").resolve(
                        "client-2.json"
                ),
                state(workers.get("client-1")),
                StandardCharsets.UTF_8
        );
        ScenarioWorkerInventory.InventoryMismatch duplicate = assertThrows(
                ScenarioWorkerInventory.InventoryMismatch.class,
                () -> ScenarioWorkerInventory.load(temporaryDirectory, spec)
        );
        assertEquals(
                List.of(workers.get("client-1")),
                duplicate.inconsistentIds()
        );
    }

    @Test
    void reportsWrongGroupAndInvalidWorkerId() throws Exception {
        FleetSpec spec = spec();
        createGroup("wrong-group", List.of("client-1", "client-2"));
        ScenarioWorkerInventory.InventoryMismatch groups = assertThrows(
                ScenarioWorkerInventory.InventoryMismatch.class,
                () -> ScenarioWorkerInventory.load(temporaryDirectory, spec)
        );
        assertEquals(List.of("group-a"), groups.missingIds());
        assertEquals(List.of("wrong-group"), groups.unexpectedIds());

        Files.move(
                temporaryDirectory.resolve("wrong-group"),
                temporaryDirectory.resolve("group-a")
        );
        Files.writeString(
                temporaryDirectory.resolve("group-a/client-2.json"),
                state("not-a-worker-id"),
                StandardCharsets.UTF_8
        );
        ScenarioWorkerInventory.InventoryMismatch invalid = assertThrows(
                ScenarioWorkerInventory.InventoryMismatch.class,
                () -> ScenarioWorkerInventory.load(temporaryDirectory, spec)
        );
        assertEquals(List.of("client-2"), invalid.inconsistentIds());
    }

    private FleetSpec spec() {
        return new FleetSpec(
                "adapter",
                Map.of("group-a", List.of("client-1", "client-2"))
        );
    }

    private Map<String, String> createGroup(
            String groupId,
            List<String> clientKeys
    ) throws Exception {
        Path group = Files.createDirectories(
                temporaryDirectory.resolve(groupId)
        );
        Map<String, String> workers = new LinkedHashMap<>();
        for (String clientKey : clientKeys) {
            String workerId = UUID.randomUUID().toString();
            workers.put(clientKey, workerId);
            Files.writeString(
                    group.resolve(clientKey + ".json"),
                    state(workerId),
                    StandardCharsets.UTF_8
            );
        }
        return workers;
    }

    private static String state(String workerId) {
        return Jsons.toJson(Map.of(
                "schemaVersion", 1,
                "workerId", workerId,
                "workerProperties", Map.of(
                        "dynamic",
                        UUID.randomUUID().toString()
                )
        ));
    }
}
