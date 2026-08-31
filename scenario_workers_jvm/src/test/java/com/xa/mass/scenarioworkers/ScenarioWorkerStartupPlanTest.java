package com.xa.mass.scenarioworkers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioWorkerStartupPlanTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void parsesStrictInitialWorkersAndScheduledStops() {
        ScenarioWorkerStartupPlan plan = ScenarioWorkerStartupPlan.parse("""
                {
                  "schemaVersion":1,
                  "initialWorkers":[
                    {
                      "workerGroupId":"group",
                      "clientWorkerKey":"worker-1"
                    }
                  ],
                  "scheduledStops":[
                    {
                      "workerGroupId":"group",
                      "clientWorkerKey":"worker-1",
                      "delayMillis":5000
                    }
                  ]
                }
                """);

        assertThat(plan.startAll()).isFalse();
        assertThat(plan.initialWorkers()).containsExactly(
                new ScenarioWorkerCoordinate("group", "worker-1")
        );
        assertThat(plan.scheduledStops()).containsExactly(
                new ScenarioWorkerStartupPlan.ScheduledStop(
                        new ScenarioWorkerCoordinate("group", "worker-1"),
                        5000L
                )
        );
    }

    @Test
    void defaultsStartAllWithoutFaults() {
        ScenarioWorkerStartupPlan plan =
                ScenarioWorkerStartupPlan.defaults();

        assertThat(plan.startAll()).isTrue();
        assertThat(plan.initialWorkers()).isEmpty();
        assertThat(plan.scheduledStops()).isEmpty();
    }

    @Test
    void rejectsUnknownFieldsDuplicatesAndStopsOutsideInitialSet() {
        assertThatThrownBy(() -> ScenarioWorkerStartupPlan.parse("""
                {
                  "schemaVersion":1,
                  "initialWorkers":[],
                  "scheduledStops":[],
                  "actions":[]
                }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly");
        assertThatThrownBy(() -> ScenarioWorkerStartupPlan.parse("""
                {
                  "schemaVersion":1,
                  "initialWorkers":[
                    {"workerGroupId":"g","clientWorkerKey":"w"},
                    {"workerGroupId":"g","clientWorkerKey":"w"}
                  ],
                  "scheduledStops":[]
                }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> ScenarioWorkerStartupPlan.parse("""
                {
                  "schemaVersion":1,
                  "initialWorkers":[],
                  "scheduledStops":[
                    {
                      "workerGroupId":"g",
                      "clientWorkerKey":"w",
                      "delayMillis":1
                    }
                  ]
                }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initial Worker");
    }

    @Test
    void loadsTheConfiguredFile() throws Exception {
        Path planFile = temporaryDirectory.resolve("startup.json");
        Files.writeString(
                planFile,
                """
                {
                  "schemaVersion":1,
                  "initialWorkers":[],
                  "scheduledStops":[]
                }
                """,
                StandardCharsets.UTF_8
        );

        assertThat(ScenarioWorkerStartupPlan.load(planFile.toString())
                .initialWorkers()).isEmpty();
    }
}
