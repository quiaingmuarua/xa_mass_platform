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

    private static final Path ENGINE_MAIN_ROOT = Path.of("src", "main", "java", "com", "xa", "mass", "engine");
    private static final Path ENGINE_TEST_ROOT = Path.of("src", "test", "java", "com", "xa", "mass", "engine");
    private static final Path ENGINE_POM = Path.of("pom.xml");

    private static final List<Class<?>> MAINLINE_SUITES = List.of(
            EngineSchedulingCoreSuite.class,
            EngineKernelConvergenceSuite.class
    );

    private static final List<Class<?>> SUPPORT_SUITES = List.of(
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

    @Test
    void engineProductionDoesNotOwnCompatibilityProjectionWrites() throws IOException {
        List<ForbiddenToken> forbiddenTokens = List.of(
                new ForbiddenToken("TaskDetailStore", "storage read-model owner"),
                new ForbiddenToken("com.xa.mass.storage.api.projection", "storage projection enum package"),
                new ForbiddenToken("TaskMessageProjection", "message projection row"),
                new ForbiddenToken("TaskMessageAttemptProjection", "attempt projection row"),
                new ForbiddenToken("TaskCompatibilityProjectionStore", "engine projection writer"),
                new ForbiddenToken("TaskWorkProjectionState", "projection-named lifecycle state"),
                new ForbiddenToken("CompatibilityProjectionOnly", "compatibility projection marker"),
                new ForbiddenToken("upsertTaskMessageProjection", "message projection write"),
                new ForbiddenToken("upsertTaskMessageAttemptProjection", "attempt projection write"),
                new ForbiddenToken("getTaskMessageProjection", "projection read"),
                new ForbiddenToken("getTaskMessageProjections", "projection read")
        );

        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(ENGINE_MAIN_ROOT)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (!path.getFileName().toString().endsWith(".java")) {
                    continue;
                }
                String source = Files.readString(path, StandardCharsets.UTF_8);
                for (ForbiddenToken token : forbiddenTokens) {
                    if (source.contains(token.value())) {
                        violations.add(path + " uses " + token.reason() + ": " + token.value());
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Engine production must stay runtime/task-shell first and must not write compatibility projection rows:\n"
                        + String.join("\n", violations));
    }

    @Test
    void engineProductionDoesNotImportStorageContracts() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(ENGINE_MAIN_ROOT)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (!path.getFileName().toString().endsWith(".java")) {
                    continue;
                }
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("import com.xa.mass.storage.")) {
                        continue;
                    }
                    String importedType = trimmed
                            .replace("import ", "")
                            .replace(";", "");
                    violations.add(path + " imports storage dependency: " + importedType);
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Engine production must not import storage contracts; use kernel SPI or engine-owned ports instead:\n"
                        + String.join("\n", violations));
    }

    @Test
    void enginePomDoesNotDeclareProductionStorageDependencies() throws IOException {
        Set<String> forbiddenArtifacts = Set.of(
                "mass-storage-api",
                "mass-storage-memory",
                "mass-storage-jdbc"
        );

        List<String> violations = new ArrayList<>();
        String pom = Files.readString(ENGINE_POM, StandardCharsets.UTF_8);
        for (String dependency : pom.split("<dependency>")) {
            if (!dependency.contains("</dependency>")) {
                continue;
            }
            String block = dependency.substring(0, dependency.indexOf("</dependency>"));
            String artifactId = tagValue(block, "artifactId");
            if (!forbiddenArtifacts.contains(artifactId)) {
                continue;
            }
            String scope = tagValue(block, "scope");
            if (!"test".equals(scope)) {
                violations.add("xa-mass-engine declares production storage dependency: " + artifactId);
            }
        }

        assertTrue(violations.isEmpty(),
                "Engine production POM must not depend on storage modules:\n"
                        + String.join("\n", violations));
    }

    @Test
    void enginePomDoesNotUseStorageImplementationAsTestFixture() throws IOException {
        String pom = Files.readString(ENGINE_POM, StandardCharsets.UTF_8);

        assertTrue(!pom.contains("<artifactId>mass-storage-memory</artifactId>"),
                "Engine tests must use engine-owned kernel fixtures for ordinary runtime proof. "
                        + "Storage implementation tests belong in the storage module.");
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
        if (!suiteClasses.isEmpty()) {
            assertTrue(!selectedClasses.isEmpty(), "Engine proof guard suites must select at least one concrete test class");
        }
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

    private static String tagValue(String xml, String tag) {
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int start = xml.indexOf(open);
        if (start < 0) {
            return null;
        }
        int end = xml.indexOf(close, start + open.length());
        if (end < 0) {
            return null;
        }
        return xml.substring(start + open.length(), end).trim();
    }

    private record ForbiddenToken(String value, String reason) {
    }
}
