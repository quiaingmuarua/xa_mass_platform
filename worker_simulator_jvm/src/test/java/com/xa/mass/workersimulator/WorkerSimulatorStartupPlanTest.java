package com.xa.mass.workersimulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerSimulatorStartupPlanTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void parsesStrictInitialWorkersAndScheduledStops() {
        WorkerSimulatorStartupPlan plan = WorkerSimulatorStartupPlan.parse("""
                {
                  "initialWorkers":[
                    {
                      "workerGroupId":"group",
                      "labWorkerKey":"worker-1"
                    }
                  ],
                  "scheduledStops":[
                    {
                      "workerGroupId":"group",
                      "labWorkerKey":"worker-1",
                      "delayMillis":5000
                    }
                  ]
                }
                """);

        assertThat(plan.startAll()).isFalse();
        assertThat(plan.initialWorkers()).containsExactly(
                new WorkerSimulatorCoordinate("group", "worker-1")
        );
        assertThat(plan.scheduledStops()).containsExactly(
                new WorkerSimulatorStartupPlan.ScheduledStop(
                        new WorkerSimulatorCoordinate("group", "worker-1"),
                        5000L
                )
        );
    }

    @Test
    void defaultsStartAllWithoutFaults() {
        WorkerSimulatorStartupPlan plan =
                WorkerSimulatorStartupPlan.defaults();

        assertThat(plan.startAll()).isTrue();
        assertThat(plan.initialWorkers()).isEmpty();
        assertThat(plan.scheduledStops()).isEmpty();
    }

    @Test
    void rejectsUnknownFieldsDuplicatesAndStopsOutsideInitialSet() {
        assertThatThrownBy(() -> WorkerSimulatorStartupPlan.parse("""
                {
                  "initialWorkers":[],
                  "scheduledStops":[],
                  "actions":[]
                }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly");
        assertThatThrownBy(() -> WorkerSimulatorStartupPlan.parse("""
                {
                  "initialWorkers":[
                    {"workerGroupId":"g","labWorkerKey":"w"},
                    {"workerGroupId":"g","labWorkerKey":"w"}
                  ],
                  "scheduledStops":[]
                }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> WorkerSimulatorStartupPlan.parse("""
                {
                  "initialWorkers":[],
                  "scheduledStops":[
                    {
                      "workerGroupId":"g",
                      "labWorkerKey":"w",
                      "delayMillis":1
                    }
                  ]
                }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initial Worker");
    }

    @Test void rejectsTheOldStandaloneVersionWrapper() {
        assertThatThrownBy(() -> WorkerSimulatorStartupPlan.parse(
                "{\"schemaVersion\":1,\"initialWorkers\":[],\"scheduledStops\":[]}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
