package com.xa.mass.integration.workerscale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScaleOptionsTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void parsesTheFixedScaleDefaultsAndRequiredEvidencePaths() {
        ScaleOptions options = ScaleOptions.parse(arguments("initial"));

        assertThat(options.phase()).isEqualTo(ScaleOptions.Phase.INITIAL);
        assertThat(options.offeredWorkers()).isEqualTo(10_000);
        assertThat(options.minimumConverged()).isEqualTo(9_900);
        assertThat(options.workloadItemsPerTask()).isEqualTo(500);
        assertThat(options.stableHold().toMillis()).isEqualTo(60_000);
        assertThat(options.baselineFile()).isAbsolute();
    }

    @Test
    void reconnectedPhaseDefaultsToNoStableHold() {
        ScaleOptions options = ScaleOptions.parse(arguments("reconnected"));

        assertThat(options.phase()).isEqualTo(ScaleOptions.Phase.RECONNECTED);
        assertThat(options.stableHold()).isZero();
    }

    @Test
    void rejectsUnknownOptionsAndImpossibleThresholds() {
        assertThatThrownBy(() -> ScaleOptions.parse(new String[]{
                "--phase=initial",
                "--baseline-file=" + temporaryDirectory.resolve("ids.json"),
                "--summary-file=" + temporaryDirectory.resolve("summary.json"),
                "--timeline-file=" + temporaryDirectory.resolve("timeline.jsonl"),
                "--unknown=value"
        })).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown scale option");

        String[] values = arguments("initial");
        String[] invalid = java.util.Arrays.copyOf(values, values.length + 2);
        invalid[values.length] = "--offered-workers=10";
        invalid[values.length + 1] = "--minimum-converged=11";
        assertThatThrownBy(() -> ScaleOptions.parse(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimumConverged");

        String[] legacy = java.util.Arrays.copyOf(values, values.length + 1);
        legacy[values.length] = "--task-item-count=100";
        assertThatThrownBy(() -> ScaleOptions.parse(legacy))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown scale option");

        String[] oversized = java.util.Arrays.copyOf(
                values,
                values.length + 1
        );
        oversized[values.length] = "--workload-items-per-task=501";
        assertThatThrownBy(() -> ScaleOptions.parse(oversized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workloadItemsPerTask");
    }

    private String[] arguments(String phase) {
        return new String[]{
                "--phase=" + phase,
                "--baseline-file=" + temporaryDirectory.resolve("ids.json"),
                "--summary-file=" + temporaryDirectory.resolve("summary.json"),
                "--timeline-file=" + temporaryDirectory.resolve("timeline.jsonl")
        };
    }
}
