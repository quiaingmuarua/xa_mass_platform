package com.xa.mass.testing.chaos.support;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xa.mass.testing.support.TestingPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class ChaosReportWriter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ChaosReportWriter() {
    }

    public static Path write(String reportPrefix, Map<String, Object> report) throws Exception {
        Path reportDir = TestingPaths.reportDir("chaos-reports");
        Files.createDirectories(reportDir);
        Path reportPath = reportDir.resolve(reportPrefix + "-" + ChaosSupport.timestampSuffix() + ".json");
        Files.writeString(reportPath, GSON.toJson(report), StandardCharsets.UTF_8);
        return reportPath;
    }
}
