package com.xa.mass.testing.soak;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SoakReportWriterTest {

    @Test
    void writesReportJsonUnderTestingTarget() throws Exception {
        String runId = "soak-report-writer-test";

        Path reportPath = SoakReportWriter.write(runId, Map.of(
                "runId", runId,
                "tasksSubmitted", 1,
                "tasksTerminal", 1,
                "proof", new SoakProofBundle(
                        new SoakInvariantReport(true, List.of()),
                        Map.of("totalResults", 1),
                        Map.of("receivedItems", 1),
                        Map.of("initialWorkerCount", 1),
                        Map.of("available", true),
                        new SoakTraceProof(true, "trace-path", Map.of("valid", true), Map.of("count", 1), 0),
                        List.of()
                ).toMap()
        ));

        assertTrue(Files.isRegularFile(reportPath));
        String content = Files.readString(reportPath);
        assertTrue(content.contains("\"runId\""));
        assertTrue(content.contains(runId));
        assertTrue(content.contains("\"proof\""));
        assertTrue(content.contains("\"runtimeInvariants\""));
        assertTrue(content.contains("\"resultSequentialRead\""));
        assertTrue(content.contains("\"trace\""));
    }
}
