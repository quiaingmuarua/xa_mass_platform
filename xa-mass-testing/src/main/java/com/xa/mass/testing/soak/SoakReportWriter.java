package com.xa.mass.testing.soak;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xa.mass.testing.support.TestingPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

final class SoakReportWriter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SoakReportWriter() {
    }

    static Path write(String runId, Map<String, Object> report) throws IOException {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        Path reportDir = TestingPaths.reportDir("soak-reports");
        Files.createDirectories(reportDir);
        Path reportPath = reportDir.resolve(runId + ".json");
        Files.writeString(reportPath, GSON.toJson(report), StandardCharsets.UTF_8);
        return reportPath;
    }
}
