package com.xa.mass.testing.confidence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformConfidenceProfileMatrixGuardTest {
    private static final Path REPO_ROOT = Path.of("..");
    private static final Path PROFILE_ALLOWLIST =
            REPO_ROOT.resolve("xa-mass-testing/proof/platform-confidence-profiles.txt");
    private static final Path WORKFLOW =
            REPO_ROOT.resolve(".github/workflows/platform-confidence.yml");
    private static final Path CONFIDENCE_SCRIPT =
            REPO_ROOT.resolve("xa-mass-testing/scripts/run-platform-confidence-smoke.sh");
    private static final Path SERVER_RESOURCES =
            REPO_ROOT.resolve("xa-mass-server/src/main/resources");
    private static final Pattern QUOTED_VALUE = Pattern.compile("\"([^\"]+)\"");

    @Test
    void workflowMatrixScriptAndProfileResourcesStayInSync() throws IOException {
        Set<String> allowlist = supportedProfiles();
        assertFalse(allowlist.isEmpty(), "platform confidence supported profile allowlist must not be empty");

        assertEquals(allowlist, workflowMatrixProfiles(),
                "platform-confidence.yml matrix must match the supported active-profile allowlist");
        assertEquals(allowlist, applicationProfileResources(),
                "new application-*.yml profiles require explicit platform confidence allowlist review");

        String script = Files.readString(CONFIDENCE_SCRIPT, StandardCharsets.UTF_8);
        assertTrue(script.contains("platform-confidence-profiles.txt"),
                "run-platform-confidence-smoke.sh must read the supported profile allowlist");
        assertFalse(script.contains("PROFILE}\" != \"memory-local\""),
                "run-platform-confidence-smoke.sh must not preserve the old hard-coded profile gate");
    }

    @Test
    void platformConfidenceSummaryKeepsApiAuthProofSignals() throws IOException {
        String script = Files.readString(CONFIDENCE_SCRIPT, StandardCharsets.UTF_8);
        for (String requiredField : List.of(
                "authMode",
                "operatorHeaderSupported",
                "fixtureHeaderDisabled",
                "sessionCookieSupported",
                "adminRouteFamilies",
                "sdkRouteFamilies",
                "credentialChecks",
                "confidenceOverlay")) {
            assertTrue(script.contains("\"" + requiredField + "\""),
                    "platform confidence summary must include " + requiredField);
        }
    }

    private static Set<String> supportedProfiles() throws IOException {
        Set<String> profiles = new LinkedHashSet<>();
        for (String line : Files.readAllLines(PROFILE_ALLOWLIST, StandardCharsets.UTF_8)) {
            String normalized = line.replaceFirst("#.*$", "").trim();
            if (!normalized.isBlank()) {
                profiles.add(normalized);
            }
        }
        return profiles;
    }

    private static Set<String> workflowMatrixProfiles() throws IOException {
        List<String> lines = Files.readAllLines(WORKFLOW, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.trim().startsWith("profile: [")) {
                Set<String> profiles = new LinkedHashSet<>();
                Matcher matcher = QUOTED_VALUE.matcher(line);
                while (matcher.find()) {
                    profiles.add(matcher.group(1));
                }
                return profiles;
            }
        }
        throw new IllegalStateException("platform-confidence.yml must declare a profile matrix");
    }

    private static Set<String> applicationProfileResources() throws IOException {
        Set<String> profiles = new LinkedHashSet<>();
        try (Stream<Path> paths = Files.list(SERVER_RESOURCES)) {
            paths.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("application-") && name.endsWith(".yml"))
                    .map(name -> name.substring("application-".length(), name.length() - ".yml".length()))
                    .sorted()
                    .forEach(profiles::add);
        }
        return profiles;
    }
}
