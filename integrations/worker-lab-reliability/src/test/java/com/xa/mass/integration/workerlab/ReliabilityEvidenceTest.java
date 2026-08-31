package com.xa.mass.integration.workerlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReliabilityEvidenceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesJsonSummaryAndPayloadFreeTimeline() throws Exception {
        ReliabilityEvidence evidence = new ReliabilityEvidence(
                "proof-1",
                temporaryDirectory
        );
        evidence.record("startup", "worker-observed", Map.of(
                "workerId", "worker-1",
                "networkState", "connected"
        ));
        evidence.writeSummary("succeeded", Map.of(
                "resultCount", 6,
                "propertiesRefreshObserved", true
        ));

        Map<String, Object> summary = Jsons.parseObject(Files.readString(
                temporaryDirectory.resolve(ReliabilityEvidence.SUMMARY_FILE),
                StandardCharsets.UTF_8
        ));
        assertThat(summary).containsEntry("proofId", "proof-1")
                .containsEntry("status", "succeeded")
                .containsEntry("resultCount", 6L);
        String timeline = Files.readString(
                temporaryDirectory.resolve(ReliabilityEvidence.TIMELINE_FILE),
                StandardCharsets.UTF_8
        );
        assertThat(timeline).contains("worker-observed", "worker-1")
                .doesNotContain("payload");
    }

    @Test
    void refusesPayloadFacts() throws Exception {
        ReliabilityEvidence evidence = new ReliabilityEvidence(
                "proof-1",
                temporaryDirectory
        );

        assertThatThrownBy(() -> evidence.record(
                "task",
                "result",
                Map.of("opaqueResultPayload", "secret")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload");
    }
}
