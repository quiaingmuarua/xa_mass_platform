package com.xa.mass.integration.workercallperformance;

import static org.assertj.core.api.Assertions.assertThat;
import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.file.Path;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JfrDiagnosticsTest {
    @TempDir Path temporary;

    @Name("xa.mass.ServerDeliveryStage")
    static class TestStage extends Event {
        public String stage;
        public String payload;
        public int batchSize;
    }
    @Name("xa.mass.HttpExecutor")
    static class TestExecutor extends Event {
        public boolean platformPool;
        public int active = -1;
        public int queued = -1;
    }
    @Name("xa.mass.HttpInitial")
    static class TestInitial extends Event {
        public String operation;
        public long elapsedNanos;
    }
    @Name("example.UnknownEvent")
    static class UnknownEvent extends Event { public String secret; }

    @Test void slowCallsReportTheirOwnThreadsWaitsWithMaskedNamesAndBounds() throws Exception {
        Path file = temporary.resolve("slow.jfr");
        long started = System.currentTimeMillis() - 1_000;
        try (var recording = new Recording()) {
            recording.enable(TestInitial.class);
            recording.enable("jdk.ThreadPark").withThreshold(java.time.Duration.ofMillis(10)).withStackTrace();
            recording.start();
            Thread slow = Thread.ofPlatform().name("http-nio-127.0.0.1-18082-exec-7").start(() -> {
                long begin = System.nanoTime();
                java.util.concurrent.locks.LockSupport.parkNanos(60_000_000L);
                var initial = new TestInitial();
                initial.operation = "REPORT_APPEND";
                initial.elapsedNanos = System.nanoTime() - begin;
                initial.commit();
            });
            slow.join();
            Thread fast = Thread.ofPlatform().name("http-nio-127.0.0.1-18082-exec-8").start(() -> {
                var initial = new TestInitial();
                initial.operation = "REPORT_APPEND";
                initial.elapsedNanos = 1_000_000L;
                initial.commit();
            });
            fast.join();
            recording.stop();
            recording.dump(file);
        }
        @SuppressWarnings("unchecked")
        var slowCalls = (java.util.Map<String, Object>) JfrDiagnostics.summarize(file, started, 120, "server").get("slowCalls");
        @SuppressWarnings("unchecked")
        var calls = (java.util.List<java.util.Map<String, Object>>) slowCalls.get("calls");
        assertThat(slowCalls).containsEntry("trackedCalls", 2).containsEntry("truncated", false).containsEntry("overflow", false);
        assertThat(calls).hasSize(2);
        var slowest = calls.getFirst();
        assertThat(slowest).containsEntry("stage", "xa.mass.HttpInitial/REPORT_APPEND")
                .containsEntry("thread", "http-nio-#.#.#.#-#-exec-#");
        assertThat((Double) slowest.get("durationMillis")).isGreaterThanOrEqualTo(50.0);
        // The wait belongs to the slow call's own thread; the fast call on another thread stays clean.
        @SuppressWarnings("unchecked")
        var waits = (java.util.Map<String, Long>) slowest.get("threadWaitMillis");
        assertThat(waits.get("ThreadPark")).isGreaterThanOrEqualTo(40L);
        assertThat((String) slowest.get("topParkSite")).startsWith("com.xa.mass.integration.workercallperformance.JfrDiagnosticsTest");
        assertThat((java.util.Map<?, ?>) calls.get(1).get("threadWaitMillis")).isEmpty();
    }

    @Test void exportWhitelistsFieldsAndLeavesMissingCoverageAndInapplicablePoolExplicit() throws Exception {
        Path file = temporary.resolve("private.jfr");
        long started = System.currentTimeMillis();
        try (var recording = new Recording()) {
            recording.enable(TestStage.class);
            recording.enable(TestExecutor.class);
            recording.enable(UnknownEvent.class);
            recording.start();
            var stage = new TestStage();
            stage.stage = "secret-worker-id";
            stage.payload = "secret-business-payload";
            stage.batchSize = 3;
            stage.commit();
            for (String name : java.util.List.of("REFILL_ROUND","CANDIDATEIZE","REFILL_OBSERVATION","WORKER_CONFIRM_REJECTED")) {
                var refill = new TestStage(); refill.stage = name; refill.batchSize = 5; refill.commit();
            }
            new TestExecutor().commit();
            var unknown = new UnknownEvent();
            unknown.secret = "secret-unknown-content";
            unknown.commit();
            recording.stop();
            recording.dump(file);
        }
        var summary = JfrDiagnostics.summarize(file, started, 120, "server");
        assertThat(summary).containsEntry("complete", false).containsEntry("cpuCoverageSamples", 0);
        String json = Jsons.toJson(summary);
        assertThat(json).contains("REFILL_ROUND", "CANDIDATEIZE", "REFILL_OBSERVATION", "WORKER_CONFIRM_REJECTED", "UNRECOGNIZED", "\"batchSize\":3", "\"active\":null", "\"queued\":null")
                .doesNotContain("secret-worker-id", "secret-business-payload", "example.UnknownEvent", "secret-unknown-content");
    }
}
