package com.xa.mass.scenarioworkers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioWorkerStateFileTest {

    private static final String WORKER_ID =
            "server-issued-worker-id";

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void resolveTemporaryDirectory() throws IOException {
        temporaryDirectory = temporaryDirectory.toRealPath();
    }

    @Test
    void migratesLegacyIdentityOutOfTheSameFileAndPreservesProperties()
            throws Exception {
        Path path = temporaryDirectory.resolve("client-1.json");
        writeWorker(path, WORKER_ID, Map.of("region", "initial"));

        ScenarioWorkerStateFile first = ScenarioWorkerStateFile.open(
                path,
                "client-1"
        );
        assertThat(first.workerProperties())
                .containsExactlyEntriesOf(Map.of("region", "initial"));

        ScenarioWorkerStateFile reopened = ScenarioWorkerStateFile.open(
                path,
                "client-1"
        );
        assertThat(reopened.workerProperties())
                .containsExactlyEntriesOf(Map.of("region", "initial"));
        assertThat(Jsons.parseObject(Files.readString(
                path,
                StandardCharsets.UTF_8
        ))).containsEntry("schemaVersion", 2L)
                .containsEntry(
                        "workerProperties",
                        Map.of("region", "initial")
                )
                .doesNotContainKey("workerId");
        try (var files = Files.list(temporaryDirectory)) {
            assertThat(files.map(item -> item.getFileName().toString()))
                    .containsExactly("client-1.json");
        }
    }

    @Test
    void acceptsVersionTwoWithoutWorkerPropertiesAsEmpty() throws Exception {
        Path path = temporaryDirectory.resolve("client-1.json");
        Files.writeString(
                path,
                "{\"schemaVersion\":2}",
                StandardCharsets.UTF_8
        );
        ScenarioWorkerStateFile state = ScenarioWorkerStateFile.open(
                path,
                "client-1"
        );

        assertThat(state.workerProperties()).isEmpty();
    }

    @Test
    void rejectsUnknownFieldsAndVersions() throws Exception {
        Path path = temporaryDirectory.resolve("client-1.json");
        Files.writeString(
                path,
                "{\"schemaVersion\":3}",
                StandardCharsets.UTF_8
        );
        assertInvalid(path);

        Files.writeString(
                path,
                "{\"schemaVersion\":2,\"extra\":true}",
                StandardCharsets.UTF_8
        );
        assertInvalid(path);

        Files.writeString(
                path,
                "{\"schemaVersion\":1,"
                        + "\"indexedPropertyUpdates\":"
                        + "{\"worker.region\":\"local\"}}",
                StandardCharsets.UTF_8
        );
        assertInvalid(path);
    }

    private void assertInvalid(Path path) {
        assertThatThrownBy(() -> ScenarioWorkerStateFile.open(
                path,
                "client-1"
        )).isInstanceOf(ScenarioWorkerAssemblyException.class)
                .extracting("errorCode")
                .isEqualTo(14013);
    }

    private static void writeWorker(
            Path path,
            String workerId,
            Map<String, Object> properties
    ) throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", 1);
        if (workerId != null) {
            value.put("workerId", workerId);
        }
        value.put("workerProperties", properties);
        Files.writeString(
                path,
                Jsons.toJson(value),
                StandardCharsets.UTF_8
        );
    }
}
