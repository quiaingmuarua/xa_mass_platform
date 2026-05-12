package com.xa.mass.server.e2e.support;

import org.junit.jupiter.api.Test;
import org.junit.platform.suite.api.SelectClasses;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerMainlineE2eArchitectureGuardTest {

    private static final List<String> MAINLINE_SUITE_CLASS_NAMES = List.of(
            "com.xa.mass.server.e2e.assignment.ServerSchedulingE2eSuite",
            "com.xa.mass.server.e2e.lifecycle.ServerLifecycleResultConvergenceSuite"
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
        for (Path file : selectedMainlineSuiteSourceFiles()) {
            if (!Files.isRegularFile(file)) {
                violations.add(file + " is selected by a mainline server E2E suite but its source file was not found");
                continue;
            }
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

    private static List<Path> selectedMainlineSuiteSourceFiles() {
        List<Path> sourceFiles = MAINLINE_SUITE_CLASS_NAMES.stream()
                .map(ServerMainlineE2eArchitectureGuardTest::loadSuiteClass)
                .map(ServerMainlineE2eArchitectureGuardTest::selectedClasses)
                .flatMap(selectedClasses -> Arrays.stream(selectedClasses.value()))
                .filter(Objects::nonNull)
                .filter(testClass -> testClass != ServerMainlineE2eArchitectureGuardTest.class)
                .distinct()
                .map(ServerMainlineE2eArchitectureGuardTest::sourcePathFor)
                .toList();
        assertTrue(!sourceFiles.isEmpty(), "Mainline server E2E suites must select at least one guarded test class");
        return sourceFiles;
    }

    private static SelectClasses selectedClasses(Class<?> suiteClass) {
        SelectClasses selectedClasses = suiteClass.getAnnotation(SelectClasses.class);
        if (selectedClasses == null) {
            throw new IllegalStateException("Mainline server E2E suite must declare @SelectClasses: "
                    + suiteClass.getName());
        }
        return selectedClasses;
    }

    private static Class<?> loadSuiteClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Mainline server E2E suite not found: " + className, e);
        }
    }

    private static Path sourcePathFor(Class<?> testClass) {
        String relativeSourcePath = testClass.getName().replace('.', '/') + ".java";
        return Path.of("src/test/java").resolve(relativeSourcePath);
    }
}
