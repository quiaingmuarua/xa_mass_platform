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
    @Name("example.UnknownEvent")
    static class UnknownEvent extends Event { public String secret; }

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
        assertThat(json).contains("UNRECOGNIZED", "\"batchSize\":3", "\"active\":null", "\"queued\":null")
                .doesNotContain("secret-worker-id", "secret-business-payload", "example.UnknownEvent", "secret-unknown-content");
    }
}
