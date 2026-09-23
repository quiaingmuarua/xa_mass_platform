package com.xa.mass.integration.workerloadedrecovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoadedRecoveryOptionsTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void parsesTheFixedLoadedRecoveryDefaultsAndRequiredEvidencePaths() {
        LoadedRecoveryOptions options = LoadedRecoveryOptions.parse(arguments(
                "initial-contraction"
        ));

        assertThat(options.stage())
                .isEqualTo(LoadedRecoveryOptions.Stage.INITIAL_CONTRACTION);
        assertThat(options.preparedWorkers()).isEqualTo(15_000);
        assertThat(options.retainedWorkers()).isEqualTo(10_000);
        assertThat(options.minimumInitialConverged()).isEqualTo(14_800);
        assertThat(options.minimumRetainedConverged()).isEqualTo(9_900);
        assertThat(options.workloadItemsPerTask()).isEqualTo(5_000);
        assertThat(options.stableHold().toMillis()).isEqualTo(60_000);
        assertThat(options.topologyFile()).isAbsolute();
        assertThat(options.baselineFile()).isAbsolute();
        assertThat(options.gateDirectory()).isAbsolute();
    }

    @Test
    void restartStagesDefaultToNoStableHold() {
        LoadedRecoveryOptions options = LoadedRecoveryOptions.parse(arguments(
                "hard-restart-2"
        ));

        assertThat(options.stage())
                .isEqualTo(LoadedRecoveryOptions.Stage.HARD_RESTART_2);
        assertThat(options.stableHold()).isZero();
    }

    @Test
    void rejectsUnknownOptionsAndImpossibleThresholds() {
        assertThatThrownBy(() -> LoadedRecoveryOptions.parse(new String[]{
                "--stage=initial-contraction",
                "--baseline-file=" + temporaryDirectory.resolve("ids.json"),
                "--gate-directory=" + temporaryDirectory.resolve("gate"),
                "--summary-file=" + temporaryDirectory.resolve("summary.json"),
                "--timeline-file=" + temporaryDirectory.resolve("timeline.jsonl"),
                "--unknown=value"
        })).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown loaded recovery option");

        String[] values = arguments("initial-contraction");
        String[] invalid = java.util.Arrays.copyOf(values, values.length + 2);
        invalid[values.length] = "--prepared-workers=10";
        invalid[values.length + 1] = "--retained-workers=10";
        assertThatThrownBy(() -> LoadedRecoveryOptions.parse(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retainedWorkers");

        String[] legacy = java.util.Arrays.copyOf(values, values.length + 1);
        legacy[values.length] = "--task-item-count=100";
        assertThatThrownBy(() -> LoadedRecoveryOptions.parse(legacy))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown loaded recovery option");

        String[] oldPhase = java.util.Arrays.copyOf(values, values.length + 1);
        oldPhase[values.length] = "--phase=initial";
        assertThatThrownBy(() -> LoadedRecoveryOptions.parse(oldPhase))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown loaded recovery option");

        String[] oversized = java.util.Arrays.copyOf(
                values,
                values.length + 1
        );
        oversized[values.length] = "--workload-items-per-task=5001";
        assertThatThrownBy(() -> LoadedRecoveryOptions.parse(oversized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workloadItemsPerTask");
    }

    private String[] arguments(String stage) {
        return new String[]{
                "--stage=" + stage,
                "--topology-file=" + temporaryDirectory.resolve("topology.json"),
                "--baseline-file=" + temporaryDirectory.resolve("ids.json"),
                "--gate-directory=" + temporaryDirectory.resolve("gate"),
                "--summary-file=" + temporaryDirectory.resolve("summary.json"),
                "--timeline-file=" + temporaryDirectory.resolve("timeline.jsonl")
        };
    }
}
