package com.xa.mass.engine;

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

import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineProofOwnershipGuardTest {

    private static final Path ENGINE_TEST_ROOT = Path.of("src", "test", "java", "com", "xa", "mass", "engine");

    private static final List<Class<?>> MAINLINE_SUITES = List.of(
            EngineSchedulingCoreSuite.class,
            EngineKernelConvergenceSuite.class
    );

    private static final List<Class<?>> SUPPORT_SUITES = List.of(
            EngineProjectionResidueSuite.class,
            EngineProjectionAuditSuite.class
    );

    @Test
    void projectionAndAuditSuitesMustNotLeakBackIntoEngineMainline() {
        Set<Class<?>> mainlineClasses = selectedClasses(MAINLINE_SUITES);
        List<String> violations = selectedClasses(SUPPORT_SUITES).stream()
                .filter(mainlineClasses::contains)
                .map(Class::getName)
                .toList();

        assertTrue(violations.isEmpty(),
                "Projection residue/audit coverage must not be selected by engine mainline suites:\n"
                        + String.join("\n", violations));
    }

    @Test
    void engineMainlineSuitesDoNotIncludeSecondaryProofClasses() {
        List<String> violations = new ArrayList<>();
        for (Class<?> testClass : selectedClasses(MAINLINE_SUITES)) {
            Tag tag = testClass.getAnnotation(Tag.class);
            if (tag != null && "secondary-proof".equals(tag.value())) {
                violations.add(testClass.getName() + " is tagged secondary-proof but still selected by an engine mainline suite");
            }
        }

        assertTrue(violations.isEmpty(),
                "Engine mainline suites must not include downgraded secondary-proof coverage:\n"
                        + String.join("\n", violations));
    }

    @Test
    void projectionResidueAndAuditSuitesOnlyReferenceSecondaryProofClasses() {
        List<String> violations = new ArrayList<>();
        for (Class<?> testClass : selectedClasses(SUPPORT_SUITES)) {
            Tag tag = testClass.getAnnotation(Tag.class);
            if (tag == null || !"secondary-proof".equals(tag.value())) {
                violations.add(testClass.getName() + " is selected by an engine projection/support suite but is not tagged secondary-proof");
            }
        }

        assertTrue(violations.isEmpty(),
                "Engine projection residue/audit suites must contain only explicitly downgraded secondary-proof coverage:\n"
                        + String.join("\n", violations));
    }

    @Test
    void downgradedEngineCoverageMustBelongToProjectionOrAuditSuites() throws IOException {
        Set<Class<?>> supportClasses = selectedClasses(SUPPORT_SUITES);
        List<String> violations = new ArrayList<>();
        for (Class<?> testClass : discoveredSecondaryProofClasses()) {
            if (!supportClasses.contains(testClass)) {
                violations.add(testClass.getName() + " is tagged secondary-proof but is not selected by an engine projection/support suite");
            }
        }

        assertTrue(violations.isEmpty(),
                "Downgraded engine coverage must live in an explicit projection residue or audit suite:\n"
                        + String.join("\n", violations));
    }

    private static Set<Class<?>> selectedClasses(List<Class<?>> suiteClasses) {
        Set<Class<?>> selectedClasses = new LinkedHashSet<>();
        for (Class<?> suiteClass : suiteClasses) {
            SelectClasses annotation = suiteClass.getAnnotation(SelectClasses.class);
            if (annotation == null) {
                throw new IllegalStateException("Suite must declare @SelectClasses: " + suiteClass.getName());
            }
            Arrays.stream(annotation.value())
                    .filter(Objects::nonNull)
                    .forEach(selectedClasses::add);
        }
        assertTrue(!selectedClasses.isEmpty(), "Engine proof guard suites must select at least one concrete test class");
        return selectedClasses;
    }

    private static Set<Class<?>> discoveredSecondaryProofClasses() throws IOException {
        Set<Class<?>> discoveredClasses = new LinkedHashSet<>();
        try (Stream<Path> paths = Files.walk(ENGINE_TEST_ROOT)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (!path.getFileName().toString().endsWith(".java")) {
                    continue;
                }
                String source = Files.readString(path, StandardCharsets.UTF_8);
                if (!source.contains("@Tag(\"secondary-proof\")")) {
                    continue;
                }
                Path relativePath = ENGINE_TEST_ROOT.relativize(path);
                String className = "com.xa.mass.engine."
                        + relativePath.toString()
                                .replace('/', '.')
                                .replace('\\', '.')
                                .replaceAll("\\.java$", "");
                discoveredClasses.add(loadClass(className));
            }
        }
        return discoveredClasses;
    }

    private static Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Engine proof guard class not found: " + className, e);
        }
    }
}
