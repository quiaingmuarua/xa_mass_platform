package com.xa.mass.workersimulator;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
