package com.xa.mass.scenarioworkers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioWorkerStateFileTest {

    private static final String WORKER_ID =
            "server-issued-worker-id";

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsIdentityInTheSameFileAndReopensTheSnapshot()
            throws Exception {
        Path path = temporaryDirectory.resolve("client-1.json");
        writeWorker(path, null, Map.of("region", "initial"));

        ScenarioWorkerStateFile first = ScenarioWorkerStateFile.open(
                path,
                "client-1"
        );
        assertThat(first.loadWorkerId()).isEmpty();
        assertThat(first.workerProperties())
                .containsExactlyEntriesOf(Map.of("region", "initial"));

        first.saveWorkerId(WORKER_ID);
        first.saveWorkerId(WORKER_ID);

        ScenarioWorkerStateFile reopened = ScenarioWorkerStateFile.open(
                path,
                "client-1"
        );
        assertThat(reopened.loadWorkerId()).contains(WORKER_ID);
        assertThat(reopened.workerProperties())
                .containsExactlyEntriesOf(Map.of("region", "initial"));
        try (var files = Files.list(temporaryDirectory)) {
            assertThat(files.map(item -> item.getFileName().toString()))
                    .containsExactly("client-1.json");
        }
    }

    @Test
    void refusesToReplaceAnExistingWorkerId() throws Exception {
        Path path = temporaryDirectory.resolve("client-1.json");
        writeWorker(path, WORKER_ID, Map.of());
        ScenarioWorkerStateFile state = ScenarioWorkerStateFile.open(
                path,
                "client-1"
        );

        assertThatThrownBy(() -> state.saveWorkerId("different-id"))
                .isInstanceOf(ScenarioWorkerAssemblyException.class)
                .extracting("errorCode")
                .isEqualTo(14013);
    }

    @Test
    void rejectsUnknownFieldsAndVersions() throws Exception {
        Path path = temporaryDirectory.resolve("client-1.json");
        Files.writeString(
                path,
                "{\"schemaVersion\":2}",
                StandardCharsets.UTF_8
        );
        assertInvalid(path);

        Files.writeString(
                path,
                "{\"schemaVersion\":1,\"extra\":true}",
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
