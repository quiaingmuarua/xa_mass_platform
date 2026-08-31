package com.xa.mass.integration.workerfleet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FleetSpecTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void checkedSpecDeclaresTwoGroupsAndTwentyReplicas() throws Exception {
        FleetSpec spec = FleetSpec.load(Path.of("fleet-spec.json"));

        assertEquals("scenario-websocket", spec.endpointManagerId());
        assertEquals(2, spec.labWorkerKeysByGroup().size());
        assertEquals(20, spec.allLabWorkerKeys().size());
        spec.labWorkerKeysByGroup().values().forEach(keys ->
                assertEquals(10, keys.size()));
    }

    @Test
    void rejectsDuplicateClientKeysAndUnknownFields() throws Exception {
        Path duplicate = write(
                "duplicate.json",
                """
                        {
                          "endpointManagerId":"adapter",
                          "groups":{"group":{"labWorkerKeys":["a","a"]}}
                        }
                        """
        );
        Path unknown = write(
                "unknown.json",
                """
                        {
                          "endpointManagerId":"adapter",
                          "groups":{"group":{"labWorkerKeys":["a"]}},
                          "extra":true
                        }
                        """
        );

        assertThrows(IllegalArgumentException.class,
                () -> FleetSpec.load(duplicate));
        assertThrows(IllegalArgumentException.class,
                () -> FleetSpec.load(unknown));
    }

    private Path write(String name, String value) throws Exception {
        Path path = temporaryDirectory.resolve(name);
        Files.writeString(path, value, StandardCharsets.UTF_8);
        return path;
    }
}
