package com.xa.mass.integration.workercallperformance;

import static org.assertj.core.api.Assertions.*;
import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CapacityEvidenceTest {
    @TempDir Path temporary;

    @Name("xa.mass.TaskSubmission")
    static class StoreEvent extends Event {
        public String stage = "RESULT_STORED", key = "";
        public boolean failed;
        public int count = 100, batchSize = 100;
        public long elapsedNanos = 1;
    }
    @Name("xa.mass.TaskDispatch")
    static class DispatchEvent extends Event {
        public String stage, key = "";
        public boolean failed;
        public int count = 100, batchSize = 100;
        public long elapsedNanos = 1;
    }
    @Name("jdk.CPULoad")
    static class CoverageEvent extends Event {}

    @Test void recordedCountsCannotOverrideExhaustedBacklogOrMissingRecording() throws Exception {
        Path file = temporary.resolve("completion.jfr");
        long start, end;
        try (var recording = new Recording()) {
            recording.enable(StoreEvent.class);
            recording.enable(DispatchEvent.class);
            recording.enable(CoverageEvent.class);
            recording.start();
            start = System.currentTimeMillis() - 1;
            new CoverageEvent().commit();
            for (String name : List.of("CLAIMED", "COMMAND_PUBLISHED")) {
                var event = new DispatchEvent(); event.stage = name; event.commit();
            }
            new StoreEvent().commit();
            end = System.currentTimeMillis() + 1;
            recording.stop();
            recording.dump(file);
        }
        var raw = new LinkedHashMap<String, Object>(Map.of("status", "passed", "exportedSuccessCount", 100L,
                "lifecycleStartedEpochMillis", start, "validationEndedEpochMillis", end,
                "measurementStartedEpochMillis", start, "measurementEndedEpochMillis", end));
        assertThat(CapacityEvidence.analyze(file, raw, null)).containsEntry("status", "passed").containsEntry("completedCount", 100L);
        raw.put("invalidReasons", List.of("items-exhausted"));
        var exhausted = CapacityEvidence.analyze(file, raw, null);
        assertThat(exhausted).containsEntry("status", "invalid").containsEntry("evidenceStatus", "incomplete");
        assertThat(ExperimentReport.meets(exhausted, 1)).isFalse();
        raw.remove("invalidReasons");
        var missing = CapacityEvidence.analyze(temporary.resolve("missing.jfr"), raw, null);
        assertThat(missing).containsEntry("status", "invalid");
        assertThat(ExperimentReport.meets(missing, 1)).isFalse();
    }

    @Test void windowExcludesWarmupTailAndSampledCopies() {
        var counts = new CapacityEvidence.Counts();
        for (long at : List.of(999L, 1000L, 10999L, 11000L)) {
            counts.add("xa.mass.TaskSubmission", "RESULT_STORED", "", false, 100, 100, 1,
                    at, 0, 12000, 1000, 11000);
            counts.add("xa.mass.TaskSubmission", "RESULT_STORED", "sample", false, 100, 100, 1,
                    at, 0, 12000, 1000, 11000);
        }
        assertThat(counts.totals).containsEntry("RESULT_STORED", 400L);
        counts.add("xa.mass.TaskSubmission", "RESULT_STORED", "", true, 100, 100, 1,
                2000, 0, 12000, 1000, 11000);
        assertThat(counts.between(1000, 11000)).isEqualTo(200);
        assertThat(CapacityEvidence.buckets(counts, 1000, 11000, 10000).getFirst())
                .containsEntry("completedPerSecond", 20.0);
    }

    @Test void dataLossMissingCoverageAndMismatchedCountsCannotValidate() {
        var counts = new CapacityEvidence.Counts();
        counts.dataLoss = true;
        counts.totals.put("RESULT_STORED", 101L);
        var reasons = new ArrayList<String>();
        CapacityEvidence.validate(counts, 100, 1000, 31000, reasons);
        assertThat(reasons).contains("jfr-data-loss", "recording-coverage", "count-mismatch-result_stored", "count-mismatch-claimed");
        counts.dataLoss = false;
        counts.totals.put("RESULT_STORED", 100L);
        counts.totals.put("CLAIMED", 100L);
        counts.totals.put("COMMAND_PUBLISHED", 100L);
        for (long at = 0; at <= 32000; at += 1000) counts.coverage.add(at);
        reasons.clear();
        CapacityEvidence.validate(counts, 100, 1000, 31000, reasons);
        assertThat(reasons).isEmpty();
    }

    @Test void exportChecksIdentityUniquenessAndActualMd5() {
        var seen = new BitSet();
        var row = Map.<String, Object>of("messageId", "p-7", "opaqueResultPayload",
                Jsons.toJson(Map.of("input", CallApi.INPUT, "valid", true, "md5", "c1bb4f81d892b2d57947682aeb252456")));
        CallApi.verifyExportRow(row, "p-", 100, seen);
        assertThat(seen.cardinality()).isEqualTo(1);
        assertThatThrownBy(() -> CallApi.verifyExportRow(row, "p-", 100, seen)).isInstanceOf(CallLoad.ProtocolFailure.class);
        assertThatThrownBy(() -> CallApi.verifyExportRow(row, "other-", 100, new BitSet())).isInstanceOf(CallLoad.ProtocolFailure.class);
        assertThatThrownBy(() -> CallApi.verifyExportRow(row, "p-", 7, new BitSet())).isInstanceOf(CallLoad.ProtocolFailure.class);
        assertThatThrownBy(() -> CallApi.verifyExportRow(Map.of("messageId", "p-8", "opaqueResultPayload", "{}"),
                "p-", 100, seen)).isInstanceOf(CallLoad.ProtocolFailure.class);
    }

    @Test void costUsesCompletionCountInsideItsOwnSampleInterval() throws Exception {
        var counts = new CapacityEvidence.Counts();
        counts.successes.addAll(List.of(new CapacityEvidence.Completion(999, 1000),
                new CapacityEvidence.Completion(2000, 100), new CapacityEvidence.Completion(11000, 1000)));
        Path samples = temporary.resolve("resources.jsonl");
        var rows = new StringBuilder();
        for (int second = 1; second <= 11; second++)
            rows.append(Jsons.toJson(Map.of("role", "server", "epochMillis", second * 1000,
                    "cpuSeconds", 1.0 + (second - 1) * .2))).append('\n');
        Files.writeString(samples, rows);
        var cost = CallApi.object(CapacityEvidence.costs(samples, counts, 0, 12000).get("server"));
        assertThat(cost).containsEntry("completedCount", 100L).containsEntry("cpuMillisPerCompleted", 20.0);
        assertThat(CapacityEvidence.costs(samples, counts, 0, 30000)).isEmpty();
    }

    @Test void leaseShareUsesTheActualConfiguredWorkerCount() {
        var samples = List.<Map<String, Object>>of(Map.of("perf-a", Map.of("held-hot", 2000),
                "perf-b", Map.of("held-hot", 1000)));
        assertThat(LaneCase.leaseHeldPeakRatio(samples, 4000)).isEqualTo(.5);
    }

    @Test void configAndAcceptanceRequireFiveCompleteLongAnyRuns() throws Exception {
        var config = ExperimentConfig.read("configs/capacity-10k.json");
        assertThat(config.settings("c16-w4000", "confirmation").workers()).isEqualTo(4000);
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 5; i++) rows.add(new LinkedHashMap<>(Map.of("status", "passed", "evidenceStatus", "complete",
                "path", "task-any", "experimentStage", "confirmation", "completedPerSecond", 11000.0, "repetition", i + 1,
                "measurementSeconds", 120, "acceptance30Seconds", java.util.Collections.nCopies(4, Map.of("completedPerSecond", 11000.0, "seconds", 30)))));
        assertThat(ExperimentReport.summarize(config, rows)).containsEntry("targetStatus", "met");
        rows.getFirst().put("acceptance30Seconds", java.util.Collections.nCopies(4, Map.of("completedPerSecond", 9999.0, "seconds", 30)));
        assertThat(ExperimentReport.summarize(config, rows)).containsEntry("targetStatus", "not-met");
        rows.getFirst().put("status", "invalid");
        assertThat(ExperimentReport.summarize(config, rows)).containsEntry("targetStatus", "inconclusive");
        rows.removeFirst();
        assertThat(ExperimentReport.summarize(config, rows)).containsEntry("targetStatus", "inconclusive");
    }

    @Test void referenceSaturationUsesOneExplicitProfileWithoutLongAcceptance() throws Exception {
        var config = ExperimentConfig.read("configs/lane-saturation.json");
        var settings = config.settings("c4-w3000", "screening");
        assertThat(settings.workers()).isEqualTo(3000);
        assertThat(settings.items()).isEqualTo(600000);
        assertThat(settings.warmupSeconds()).isEqualTo(15);
        assertThat(settings.seconds()).isEqualTo(30);
        assertThat(settings.referenceLane()).isTrue();
        assertThat(ExperimentConfig.read("configs/capacity-10k.json").settings("c4-w1000", "screening").referenceLane()).isFalse();
        assertThat(((Number) config.object("resources").get("serverHeapMiB")).intValue()).isEqualTo(1024);
        assertThat(((Number) config.object("resources").get("fileDescriptors")).intValue()).isEqualTo(16384);
        var multiple = new LinkedHashMap<>(config.values());
        multiple.put("profiles", List.of(config.profiles().getFirst(),
                Map.of("name", "other", "cpuCount", 4, "workersPerGroup", 4000)));
        assertThatThrownBy(() -> new ExperimentConfig(multiple)).isInstanceOf(IllegalArgumentException.class);
        var invalid = new LinkedHashMap<>(config.values());
        invalid.put("screening", Map.of("warmupSeconds", 15, "measurementSeconds", 30, "itemsPerGroup", 600001));
        assertThatThrownBy(() -> new ExperimentConfig(invalid)).isInstanceOf(IllegalArgumentException.class);
    }
}
