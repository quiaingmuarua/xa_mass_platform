package com.xa.mass.integration.workerscale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScaleProcessGateTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void resumesOnlyTheExactReadyCheckpoint() throws Exception {
        ScaleOptions options = options("hard-restart-1", 2_000);
        ScaleProcessGate gate = new ScaleProcessGate(options);
        CompletableFuture<ScaleProcessGate.GateResume> waiting =
                CompletableFuture.supplyAsync(
                        () -> gate.awaitServerMutation(10, 20, 80)
                );
        Path ready = options.gateDirectory().resolve("mutation-ready.json");
        awaitFile(ready);

        Map<String, Object> value = ScaleEvidence.readObject(ready, "ready");
        assertThat(value).containsEntry("taskCount", 10L)
                .containsEntry("succeededItems", 20L)
                .containsEntry("unresolvedItems", 80L);
        ScaleEvidence.writeSummary(
                options.gateDirectory().resolve("resume.json"),
                Map.of(
                        "proofId", options.proofId(),
                        "stage", options.stage().wireValue(),
                        "checkpoint", "server-mutation",
                        "resumedAtEpochMillis", System.currentTimeMillis()
                )
        );

        assertThat(waiting.get(2, TimeUnit.SECONDS).waitMillis())
                .isGreaterThanOrEqualTo(0);
    }

    @Test
    void rejectsResumeForAnotherStage() throws Exception {
        ScaleOptions options = options("graceful-restart", 2_000);
        ScaleProcessGate gate = new ScaleProcessGate(options);
        CompletableFuture<ScaleProcessGate.GateResume> waiting =
                CompletableFuture.supplyAsync(
                        () -> gate.awaitServerMutation(10, 20, 80)
                );
        awaitFile(options.gateDirectory().resolve("mutation-ready.json"));
        ScaleEvidence.writeSummary(
                options.gateDirectory().resolve("resume.json"),
                Map.of(
                        "proofId", options.proofId(),
                        "stage", "hard-restart-1",
                        "checkpoint", "server-mutation",
                        "resumedAtEpochMillis", System.currentTimeMillis()
                )
        );

        assertThatThrownBy(() -> waiting.get(2, TimeUnit.SECONDS))
                .hasRootCauseMessage(
                        "Worker WebSocket scale response is invalid: "
                                + "Scale process gate resume identity changed"
                );
    }

    @Test
    void rejectsAResumeWrittenBeforeTheReadyCheckpoint() {
        ScaleOptions options = options("hard-restart-2", 2_000);
        ScaleEvidence.writeSummary(
                options.gateDirectory().resolve("resume.json"),
                Map.of("stale", true)
        );

        assertThatThrownBy(() -> new ScaleProcessGate(options)
                .awaitServerMutation(10, 20, 80))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not exist before checkpoint");
    }

    @Test
    void timesOutWithoutAProcessOwnerResume() {
        ScaleOptions options = options("graceful-restart", 100);

        assertThatThrownBy(() -> new ScaleProcessGate(options)
                .awaitServerMutation(10, 20, 80))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Timed out waiting for scale process gate");
    }

    private ScaleOptions options(String stage, long maximumWaitMillis) {
        return ScaleOptions.parse(new String[]{
                "--stage=" + stage,
                "--prepared-workers=2",
                "--retained-workers=1",
                "--minimum-initial-converged=2",
                "--minimum-retained-converged=1",
                "--maximum-convergence-wait-millis=" + maximumWaitMillis,
                "--topology-file=" + temporaryDirectory.resolve("topology.json"),
                "--baseline-file=" + temporaryDirectory.resolve("baseline.json"),
                "--gate-directory=" + temporaryDirectory.resolve(stage),
                "--summary-file=" + temporaryDirectory.resolve("summary.json"),
                "--timeline-file=" + temporaryDirectory.resolve("timeline.jsonl")
        });
    }

    private static void awaitFile(Path path) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!Files.exists(path) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(path).exists();
    }
}
