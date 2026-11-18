package com.xa.mass.workersimulator;

import static org.assertj.core.api.Assertions.*;
import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerSimulatorMainTest {
    @Test void onlyOneConfigurationFileOrHelpIsAccepted() throws Exception {
        assertThat(WorkerSimulatorMain.configPath(new String[]{"--config", "sim.json"})).isEqualTo(Path.of("sim.json"));
        assertThat(WorkerSimulatorMain.configPath(new String[]{"--config=sim.json"})).isEqualTo(Path.of("sim.json"));
        assertThat(WorkerSimulatorMain.configPath(new String[]{"--help"})).isNull();
        WorkerSimulatorMain.main(new String[]{"--help"});
        for (String[] args : new String[][]{
                {}, {"--config"}, {"--config="}, {"--config", " "},
                {"--config=a", "--config=b"}, {"--help", "--config=a"},
                {"--scenario=lab"}, {"--device-counts=1,1,1"}, {"--startup-plan=a"},
                {"--capability-assembly=a"}, {"--sandbox-root=a"}, {"--control-port=1"},
                {"--runtime-api-base-url=http://localhost"}}) {
            assertThatThrownBy(() -> WorkerSimulatorMain.configPath(args)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void pathsResolveFromTheConfigurationFileAndExamplesResolveToCompleteConfigurations(@TempDir Path directory) throws Exception {
        String example = Files.readString(Path.of("config/lab.json"));
        Path file = directory.resolve("simulator.json");
        Files.writeString(file, example);
        WorkerSimulatorConfig config = WorkerSimulatorConfig.load(file);
        assertThat(config.sandboxRoot()).isEqualTo(directory.resolve("data/scenario-workers"));
        assertThat(config.workerGroups()).extracting(WorkerSimulatorGroupConfig::workerGroupId)
                .containsExactly("scenario-phone-number-workers", "scenario-string-utils-workers");
        assertThat(config.startupPlan().startAll()).isTrue();
        assertThat(config.workerGroups()).extracting(WorkerSimulatorGroupConfig::count).containsExactly(50, 50);
        for (String name : new String[]{"lab", "sms", "messages", "products"}) {
            WorkerSimulatorConfig loaded = WorkerSimulatorConfig.load(Path.of("config/" + name + ".json"));
            try (WorkerSimulator ignored = WorkerSimulator.create(loaded)) {
                assertThat(loaded.workerGroups()).isNotEmpty();
            }
        }
    }

    @Test void explicitEmptyNumberTemplateIsNotRepairedByDefaults(@TempDir Path directory) {
        var config = WorkerSimulatorJsonParser.parse("""
                {"workerGroups":{"demo-sim":{"count":1,"propertiesTemplate":{}}},
                 "startupPlan":{"initialWorkers":[],"scheduledStops":[]}}
                """, directory);
        try (WorkerSimulator simulator = WorkerSimulator.create(config)) {
            assertThatThrownBy(() -> simulator.start(config.startupPlan()))
                    .isInstanceOf(WorkerSimulatorAssemblyException.class)
                    .hasRootCauseMessage("Number capabilities require phone and uppercase two-letter country");
        }
        assertThat(directory.resolve("data/scenario-workers/demo-sim")).doesNotExist();
    }

    @Test void persistentDistributionExamplePassesActualHostInventoryAdmission(@TempDir Path directory) throws Exception {
        Path example = Path.of("../distribution/worker-simulator/src/main/dist/examples/persistent-20-host.json");
        var input = new LinkedHashMap<>(Jsons.parseObject(Files.readString(example)));
        String deployedRoot = (String) input.get("sandboxRoot");
        String statePrefix = "/var/lib/xa-mass-worker/";
        assertThat(deployedRoot).startsWith(statePrefix);
        // Rebase only the environment prefix; preserve the example's suffix for real startup validation.
        Path sandbox = directory.resolve(deployedRoot.substring(statePrefix.length()));
        input.put("sandboxRoot", sandbox.toString());
        Path credential = directory.resolve("private/worker-access.json");
        Files.createDirectories(credential.getParent());
        Files.writeString(credential, Jsons.toJson(Map.of("keyId", "test-persistent", "secret", "test-only-secret")));
        Path configuration = directory.resolve("persistent-20-host.json");
        Files.writeString(configuration, Jsons.toJson(input));
        WorkerSimulatorConfig config = WorkerSimulatorConfig.load(configuration);
        var noConnections = WorkerSimulatorStartupPlan.parse(Map.of("initialWorkers", List.of(), "scheduledStops", List.of()));
        try (WorkerSimulator simulator = WorkerSimulator.create(config)) {
            simulator.start(noConnections);
            assertThat(simulator.workerSnapshots()).hasSize(20).allSatisfy(worker -> {
                assertThat(worker.workerGroupId()).isEqualTo("persistent-java-workers");
                assertThat(worker.runtime().state()).isEqualTo(com.xa.mass.worker.runtime.WorkerLifecycle.State.STOPPED);
            });
        }
        assertThat(sandbox.resolve("persistent-java-workers/workers-000.jsonl")).isRegularFile();
    }
}
