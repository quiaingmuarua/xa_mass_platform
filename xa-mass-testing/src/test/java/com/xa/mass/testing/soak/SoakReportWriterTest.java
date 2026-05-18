package com.xa.mass.testing.soak;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SoakReportWriterTest {

    @Test
    void writesReportJsonUnderTestingTarget() throws Exception {
        String runId = "soak-report-writer-test";

        Path reportPath = SoakReportWriter.write(runId, Map.of(
                "runId", runId,
                "tasksSubmitted", 1,
                "tasksTerminal", 1
        ));

        assertTrue(Files.isRegularFile(reportPath));
        String content = Files.readString(reportPath);
        assertTrue(content.contains("\"runId\""));
        assertTrue(content.contains(runId));
    }
}
