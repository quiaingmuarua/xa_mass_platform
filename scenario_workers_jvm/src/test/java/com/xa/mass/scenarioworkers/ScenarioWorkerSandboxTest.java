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

class ScenarioWorkerSandboxTest {

    private static final String WORKER_ID =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsIdentityAndLetsThePropertiesFileOwnLaterSnapshots()
            throws Exception {
        Path directory = temporaryDirectory.resolve("worker");
        try (ScenarioWorkerSandbox sandbox =
                     ScenarioWorkerSandbox.open(
                             directory,
                             "scenario-group",
                             "client-1",
                             Map.of("region", "initial")
                     )) {
            assertThat(sandbox.workerId()).isEmpty();
            assertThat(sandbox.workerProperties())
                    .containsEntry("region", "initial");
            sandbox.storeWorkerId(WORKER_ID);
        }

        Map<String, Object> identity = Jsons.parseObject(
                Files.readString(
                        directory.resolve("identity.json"),
                        StandardCharsets.UTF_8
                )
        );
        assertThat(identity).containsExactlyEntriesOf(
                identity("scenario-group", "client-1", WORKER_ID)
        );
        Files.writeString(
                directory.resolve("worker-properties.json"),
                Jsons.toJson(Map.of("region", "edited")),
                StandardCharsets.UTF_8
        );

        try (ScenarioWorkerSandbox sandbox =
                     ScenarioWorkerSandbox.open(
                             directory,
                             "scenario-group",
                             "client-1",
                             Map.of("region", "profile-change")
                     )) {
            assertThat(sandbox.workerId()).contains(WORKER_ID);
            assertThat(sandbox.workerProperties())
                    .containsExactlyEntriesOf(Map.of(
                            "region",
                            "edited"
                    ));
        }
    }

    @Test
    void rejectsInvalidIdentityCoordinatesAndWorkerId() throws Exception {
        Path directory = temporaryDirectory.resolve("worker");
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("worker-properties.json"),
                "{}",
                StandardCharsets.UTF_8
        );
        Files.writeString(
                directory.resolve("identity.json"),
                Jsons.toJson(identity(
                        "other-group",
                        "client-1",
                        WORKER_ID
                )),
                StandardCharsets.UTF_8
        );

        assertThatThrownBy(() -> ScenarioWorkerSandbox.open(
                directory,
                "scenario-group",
                "client-1",
                Map.of()
        )).isInstanceOf(ScenarioWorkerAssemblyException.class)
                .extracting("errorCode")
                .isEqualTo(14013);

        Files.writeString(
                directory.resolve("identity.json"),
                Jsons.toJson(identity(
                        "scenario-group",
                        "client-1",
                        "not-a-worker-id"
                )),
                StandardCharsets.UTF_8
        );
        assertThatThrownBy(() -> ScenarioWorkerSandbox.open(
                directory,
                "scenario-group",
                "client-1",
                Map.of()
        )).isInstanceOf(ScenarioWorkerAssemblyException.class)
                .extracting("errorCode")
                .isEqualTo(14013);
    }

    @Test
    void rejectsMalformedPropertiesBeforeIdentityUse() throws Exception {
        Path directory = temporaryDirectory.resolve("worker");
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("worker-properties.json"),
                "[]",
                StandardCharsets.UTF_8
        );

        assertThatThrownBy(() -> ScenarioWorkerSandbox.open(
                directory,
                "scenario-group",
                "client-1",
                Map.of()
        )).isInstanceOf(ScenarioWorkerAssemblyException.class)
                .extracting("errorCode")
                .isEqualTo(14013);
    }

    @Test
    void ownsAnExclusiveLockAndReleasesItOnClose() {
        Path directory = temporaryDirectory.resolve("worker");
        ScenarioWorkerSandbox first = ScenarioWorkerSandbox.open(
                directory,
                "scenario-group",
                "client-1",
                Map.of()
        );
        try {
            assertThatThrownBy(() -> ScenarioWorkerSandbox.open(
                    directory,
                    "scenario-group",
                    "client-1",
                    Map.of()
            )).isInstanceOf(ScenarioWorkerAssemblyException.class)
                    .extracting("errorCode")
                    .isEqualTo(14014);
        } finally {
            first.close();
        }

        ScenarioWorkerSandbox reopened = ScenarioWorkerSandbox.open(
                directory,
                "scenario-group",
                "client-1",
                Map.of()
        );
        reopened.close();
    }

    @Test
    void rejectsAFileWhereTheSandboxDirectoryMustBe() throws Exception {
        Path file = temporaryDirectory.resolve("worker");
        Files.writeString(file, "occupied", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ScenarioWorkerSandbox.open(
                file,
                "scenario-group",
                "client-1",
                Map.of()
        )).isInstanceOf(ScenarioWorkerAssemblyException.class)
                .extracting("errorCode")
                .isEqualTo(14014);
    }

    private static Map<String, Object> identity(
            String workerGroupId,
            String clientWorkerKey,
            String workerId
    ) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("workerGroupId", workerGroupId);
        identity.put("clientWorkerKey", clientWorkerKey);
        identity.put("workerId", workerId);
        return identity;
    }
}
