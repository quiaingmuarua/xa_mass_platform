package com.xa.mass.server.e2e.support;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerMainlineE2eArchitectureGuardTest {

    private static final List<Path> MAINLINE_E2E_FILES = List.of(
            source("assignment", "TaskApiMultiTaskAssignmentIntegrationTest.java"),
            source("assignment", "TaskApiMinimumWorkerGateIntegrationTest.java"),
            source("assignment", "TaskApiDelayedWorkerAvailabilityIntegrationTest.java"),
            source("assignment", "TaskApiSingleWorkerReuseIntegrationTest.java"),
            source("assignment", "TaskApiWorkerContextAttributeRoutingIntegrationTest.java"),
            source("assignment", "TaskApiWorkerWithoutContextIntegrationTest.java"),
            source("assignment", "PollingWorkerTaskFlowIntegrationTest.java"),
            source("assignment", "ExternalWorkerPollingApiIntegrationTest.java"),
            source("assignment", "TransportChannelWiringIntegrationTest.java"),
            source("lifecycle", "TaskApiIntegrationTest.java"),
            source("lifecycle", "TaskApiLifecycleGuardsIntegrationTest.java"),
            source("lifecycle", "TaskApiBlockedRunningIntegrationTest.java"),
            source("lifecycle", "TaskApiPauseCompletionIntegrationTest.java"),
            source("lifecycle", "TaskApiResumeAndCompleteIntegrationTest.java"),
            source("lifecycle", "TaskApiTerminateRunningIntegrationTest.java"),
            resultSource("TaskApiFailureResultIntegrationTest.java"),
            resultSource("TaskApiMixedResultsIntegrationTest.java"),
            resultSource("TaskApiAllMessagesFailedIntegrationTest.java")
    );

    private static final Map<String, Pattern> FORBIDDEN_MAINLINE_PATTERNS = Map.ofEntries(
            Map.entry("waitForTaskSnapshot", Pattern.compile("\\bwaitForTaskSnapshot\\b")),
            Map.entry("TaskSnapshot", Pattern.compile("\\bTaskSnapshot\\b")),
            Map.entry("fetchTaskSnapshot", Pattern.compile("\\bfetchTaskSnapshot\\b")),
            Map.entry("fetchTaskMessageAttempts", Pattern.compile("\\bfetchTaskMessageAttempts\\b")),
            Map.entry("TaskMessageProjection", Pattern.compile("\\bTaskMessageProjection\\b")),
            Map.entry("TaskMessageAttemptProjection", Pattern.compile("\\bTaskMessageAttemptProjection\\b")),
            Map.entry("getTaskMessage", Pattern.compile("\\bgetTaskMessage")),
            Map.entry("latestAttempt", Pattern.compile("\\blatestAttempt")),
            Map.entry("var", Pattern.compile("\\bvar\\b"))
    );

    @Test
    void mainlineServerE2eSuitesDoNotUseProjectionFirstProofHelpers() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : MAINLINE_E2E_FILES) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            FORBIDDEN_MAINLINE_PATTERNS.forEach((label, pattern) -> {
                if (pattern.matcher(source).find()) {
                    violations.add(file + " uses forbidden mainline token: " + label);
                }
            });
        }

        assertTrue(violations.isEmpty(),
                "Mainline server E2E suites must use runtime/aggregate proof surfaces, not projection-first helpers:\n"
                        + String.join("\n", violations));
    }

    private static Path source(String packageName, String fileName) {
        return Path.of(
                "src",
                "test",
                "java",
                "com",
                "xa",
                "mass",
                "server",
                "e2e",
                packageName,
                fileName
        );
    }

    private static Path resultSource(String fileName) {
        return source("results", fileName);
    }
}
