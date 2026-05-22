package com.xa.mass.server.e2e.support;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.platform.suite.api.SelectClasses;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerProofOwnershipGuardTest {

    private static final List<String> MAINLINE_SUITE_CLASS_NAMES = List.of(
            "com.xa.mass.server.e2e.assignment.ServerSchedulingE2eSuite",
            "com.xa.mass.server.e2e.lifecycle.ServerLifecycleResultConvergenceSuite",
            "com.xa.mass.server.e2e.assignment.ExternalWorkerParitySuite"
    );

    private static final List<String> SUPPORT_SUITE_CLASS_NAMES = List.of(
            "com.xa.mass.server.e2e.assignment.ServerSupportCoverageSuite",
            "com.xa.mass.server.e2e.lifecycle.ServerLifecycleSupportCoverageSuite",
            "com.xa.mass.server.e2e.assignment.ServerStorageCompatibilitySuite",
            "com.xa.mass.server.e2e.assignment.ServerProjectionResidueSuite",
            "com.xa.mass.server.e2e.audit.ServerProjectionAuditSuite"
    );

    private static final Set<String> RETIRED_TEST_SIMPLE_NAMES = Set.of(
            "PollingWorkerTaskFlowIntegrationTest",
            "TaskApiAssignmentTraceObservedIntegrationTest",
            "TransportChannelWiringIntegrationTest",
            "TaskApiAllMessagesFailedIntegrationTest",
            "TaskApiCallbackReplayIntegrationTest",
            "TaskApiDelayedWorkerAvailabilityIntegrationTest",
            "TaskApiMinimumWorkerGateIntegrationTest",
            "TaskApiMixedResultsIntegrationTest",
            "TaskApiSingleWorkerReuseIntegrationTest",
            "TaskApiWorkerAttributeRoutingIntegrationTest"
    );

    private static final Path PROOF_REGISTRY = Path.of("..", "doc", "PROOF_REGISTRY.md");
    private static final Path SERVER_README = Path.of("README.md");
    private static final Path SERVER_E2E_TEST_ROOT = Path.of("src", "test", "java", "com", "xa", "mass", "server", "e2e");

    @Test
    void mainlineSuitesOnlyReferenceRegistryBackedProofClasses() throws IOException {
        String registry = Files.readString(PROOF_REGISTRY, StandardCharsets.UTF_8);
        List<String> violations = new ArrayList<>();
        for (Class<?> testClass : selectedMainlineClasses()) {
            if (testClass == ServerMainlineE2eArchitectureGuardTest.class
                    || testClass == ServerProofOwnershipGuardTest.class) {
                continue;
            }
            if (!containsWholeWord(registry, testClass.getSimpleName())) {
                violations.add(testClass.getName() + " is selected by a mainline suite but is not named in doc/PROOF_REGISTRY.md");
            }
        }

        assertTrue(violations.isEmpty(),
                "Mainline server proof suites must stay registry-backed. "
                        + "Add the class to doc/PROOF_REGISTRY.md or move it out of the mainline suite:\n"
                        + String.join("\n", violations));
    }

    @Test
    void mainlineSuitesDoNotIncludeSecondaryProofClasses() {
        List<String> violations = new ArrayList<>();
        for (Class<?> testClass : selectedMainlineClasses()) {
            Tag tag = testClass.getAnnotation(Tag.class);
            if (tag != null && "secondary-proof".equals(tag.value())) {
                violations.add(testClass.getName() + " is tagged secondary-proof but still selected by a mainline suite");
            }
        }

        assertTrue(violations.isEmpty(),
                "Mainline server proof suites must not include downgraded secondary-proof coverage:\n"
                        + String.join("\n", violations));
    }

    @Test
    void supportCoverageSuitesMustNotLeakBackIntoMainlineSuites() {
        Set<Class<?>> mainlineClasses = selectedMainlineClasses();
        List<String> violations = selectedSupportClasses().stream()
                .filter(mainlineClasses::contains)
                .map(Class::getName)
                .toList();

        assertTrue(violations.isEmpty(),
                "Support/compatibility coverage must not be selected by mainline server suites:\n"
                        + String.join("\n", violations));
    }

    @Test
    void supportAndCompatibilitySuitesOnlyReferenceSecondaryProofClasses() {
        List<String> violations = new ArrayList<>();
        for (Class<?> testClass : selectedSupportClasses()) {
            Tag tag = testClass.getAnnotation(Tag.class);
            if (tag == null || !"secondary-proof".equals(tag.value())) {
                violations.add(testClass.getName() + " is selected by a support/compatibility suite but is not tagged secondary-proof");
            }
        }

        assertTrue(violations.isEmpty(),
                "Support and compatibility suites must contain only explicitly downgraded secondary-proof coverage:\n"
                        + String.join("\n", violations));
    }

    @Test
    void secondaryProofCoverageMustBelongToExplicitSupportOrCompatibilitySuites() throws IOException {
        Set<Class<?>> supportClasses = selectedSupportClasses();
        List<String> violations = new ArrayList<>();
        for (Class<?> testClass : discoveredSecondaryProofClasses()) {
            if (!supportClasses.contains(testClass)) {
                violations.add(testClass.getName() + " is tagged secondary-proof but is not selected by any explicit support/compatibility suite");
            }
        }

        assertTrue(violations.isEmpty(),
                "Downgraded server coverage must live in an explicit support or compatibility suite:\n"
                        + String.join("\n", violations));
    }

    @Test
    void retiredLowValueSmokesMustStayOutOfMainlineAndMainlineReadmeLists() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Class<?> testClass : selectedMainlineClasses()) {
            if (RETIRED_TEST_SIMPLE_NAMES.contains(testClass.getSimpleName())) {
                violations.add(testClass.getName() + " was retired but reappeared in a mainline suite");
            }
        }

        String serverReadme = Files.readString(SERVER_README, StandardCharsets.UTF_8);
        for (String retiredName : RETIRED_TEST_SIMPLE_NAMES) {
            if (containsWholeWord(serverReadme, retiredName)) {
                violations.add("README.md still references retired test " + retiredName);
            }
        }
        try (Stream<Path> paths = Files.walk(SERVER_E2E_TEST_ROOT)) {
            Set<String> retiredFileNames = RETIRED_TEST_SIMPLE_NAMES.stream()
                    .map(name -> name + ".java")
                    .collect(java.util.stream.Collectors.toSet());
            paths.filter(Files::isRegularFile)
                    .filter(path -> retiredFileNames.contains(path.getFileName().toString()))
                    .map(Path::toString)
                    .forEach(path -> violations.add("retired low-value test still exists: " + path));
        }

        assertTrue(violations.isEmpty(),
                "Retired low-value server smokes must stay retired:\n"
                        + String.join("\n", violations));
    }

    private static Set<Class<?>> selectedMainlineClasses() {
        return selectedClassesFromSuites(MAINLINE_SUITE_CLASS_NAMES);
    }

    private static Set<Class<?>> selectedSupportClasses() {
        return selectedClassesFromSuites(SUPPORT_SUITE_CLASS_NAMES);
    }

    private static Set<Class<?>> discoveredSecondaryProofClasses() throws IOException {
        Set<Class<?>> discoveredClasses = new LinkedHashSet<>();
        try (Stream<Path> paths = Files.walk(SERVER_E2E_TEST_ROOT)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (!path.getFileName().toString().endsWith(".java")) {
                    continue;
                }
                String source = Files.readString(path, StandardCharsets.UTF_8);
                if (!source.contains("@Tag(\"secondary-proof\")")) {
                    continue;
                }
                Path relativePath = SERVER_E2E_TEST_ROOT.relativize(path);
                String className = "com.xa.mass.server.e2e."
                        + relativePath.toString()
                                .replace('/', '.')
                                .replace('\\', '.')
                                .replaceAll("\\.java$", "");
                discoveredClasses.add(loadClass(className));
            }
        }
        return discoveredClasses;
    }

    private static Set<Class<?>> selectedClassesFromSuites(List<String> suiteClassNames) {
        Set<Class<?>> selectedClasses = new LinkedHashSet<>();
        for (String suiteClassName : suiteClassNames) {
            Class<?> suiteClass = loadClass(suiteClassName);
            SelectClasses selectClasses = suiteClass.getAnnotation(SelectClasses.class);
            if (selectClasses == null) {
                throw new IllegalStateException("Suite must declare @SelectClasses: " + suiteClass.getName());
            }
            Arrays.stream(selectClasses.value())
                    .filter(Objects::nonNull)
                    .forEach(selectedClasses::add);
        }
        assertTrue(!selectedClasses.isEmpty(), "Guarded server suites must select at least one concrete test class");
        return selectedClasses;
    }

    private static boolean containsWholeWord(String text, String token) {
        return Pattern.compile("\\b" + Pattern.quote(token) + "\\b").matcher(text).find();
    }

    private static Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Suite class not found: " + className, e);
        }
    }
}
