package com.xa.mass.engine;

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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineSchedulingCoreArchitectureGuardTest {

    private static final Path MAIN_SOURCE_ROOT = Path.of("src/main/java");
    private static final Path TEST_SOURCE_ROOT = Path.of("src/test/java");

    private static final Map<String, Pattern> FORBIDDEN_MAINLINE_PATTERNS = Map.ofEntries(
            Map.entry("TaskMessageProjection", Pattern.compile("\\bTaskMessageProjection\\b")),
            Map.entry("TaskMessageAttemptProjection", Pattern.compile("\\bTaskMessageAttemptProjection\\b")),
            Map.entry("CompatibilityProjection", Pattern.compile("\\bCompatibilityProjection")),
            Map.entry("ProjectionTestViews", Pattern.compile("\\bProjectionTestViews\\b")),
            Map.entry("getTaskMessage", Pattern.compile("\\bgetTaskMessage")),
            Map.entry("var", Pattern.compile("\\bvar\\b"))
    );

    @Test
    void schedulingCoreMainlineTestsDoNotUseCompatibilityProjectionAsProofSurface() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path path : selectedSuiteSourceFiles()) {
            if (!Files.isRegularFile(path)) {
                violations.add(path + " is selected by EngineSchedulingCoreSuite but its source file was not found");
                continue;
            }
            String source = Files.readString(path, StandardCharsets.UTF_8);
            for (Map.Entry<String, Pattern> forbiddenPattern : FORBIDDEN_MAINLINE_PATTERNS.entrySet()) {
                if (forbiddenPattern.getValue().matcher(source).find()) {
                    violations.add(path + " uses forbidden scheduling-core token: " + forbiddenPattern.getKey());
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Scheduling-core mainline tests must stay runtime/aggregate/trace-first:\n"
                        + String.join("\n", violations));
    }

    @Test
    void schedulingCoreRuleFixturesDoNotGrowWorkerContextRuleSurface() throws IOException {
        Pattern legacyRuleSurface = Pattern.compile(
                "\\brule\\s*\\([^;]*\\b(?:workerContext|isWorkerContext)[A-Za-z0-9_]*",
                Pattern.DOTALL
        );

        List<String> violations = new ArrayList<>();
        for (Path path : selectedSuiteSourceFiles()) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            if (legacyRuleSurface.matcher(source).find()) {
                violations.add(path + " adds a rule fixture using legacy workerContext rule variables");
            }
        }

        assertTrue(violations.isEmpty(),
                "New scheduling-core rule fixtures must use workerScheduling* / isWorkerScheduling* variables. "
                        + "Keep workerContext* only as transitional read-model assertions until retirement:\n"
                        + String.join("\n", violations));
    }

    @Test
    void listenerOrchestrationDoesNotCallDispatchCleanupPrimitivesDirectly() throws IOException {
        Map<String, Pattern> forbiddenPatterns = Map.ofEntries(
                Map.entry("releaseWorkerReservation", Pattern.compile("\\breleaseWorkerReservation\\s*\\(")),
                Map.entry("unlockWorker", Pattern.compile("\\bunlockWorker\\s*\\(")),
                Map.entry("workerLockReleased", Pattern.compile("\\bworkerLockReleased\\s*\\("))
        );

        List<String> violations = new ArrayList<>();
        Path listenerRoot = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/listener");
        for (Path path : javaSourceFiles(listenerRoot)) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            for (Map.Entry<String, Pattern> forbiddenPattern : forbiddenPatterns.entrySet()) {
                if (forbiddenPattern.getValue().matcher(source).find()) {
                    violations.add(path + " calls dispatch cleanup primitive directly: "
                            + forbiddenPattern.getKey());
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Listener/binder orchestration must consume WorkerDispatchResourceReleaser "
                        + "instead of duplicating reservation release, worker unlock, or lock-release trace:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerContextStateMutationStaysBehindLegacyLifecycleOwner() throws IOException {
        Map<String, Pattern> forbiddenPatterns = Map.ofEntries(
                Map.entry("getWorkerContextById", Pattern.compile("\\bgetWorkerContextById\\s*\\(")),
                Map.entry("updateWorkerContextById", Pattern.compile("\\bupdateWorkerContextById\\s*\\(")),
                Map.entry("deleteWorkerContextById", Pattern.compile("\\bdeleteWorkerContextById\\s*\\(")),
                Map.entry("addWorkerContext", Pattern.compile("\\baddWorkerContext\\s*\\(")),
                Map.entry("bindToTask", Pattern.compile("\\.bindToTask\\s*\\(")),
                Map.entry("startOccupying", Pattern.compile("\\.startOccupying\\s*\\("))
        );

        List<String> violations = new ArrayList<>();
        for (Path path : javaSourceFiles(MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine"))) {
            if (isWorkerManager(path) || isLegacyWorkerContextLifecycle(path)) {
                continue;
            }
            String source = Files.readString(path, StandardCharsets.UTF_8);
            for (Map.Entry<String, Pattern> forbiddenPattern : forbiddenPatterns.entrySet()) {
                if (forbiddenPattern.getValue().matcher(source).find()) {
                    violations.add(path + " reaches WorkerContext state owner directly: "
                            + forbiddenPattern.getKey());
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "WorkerContext state mutation must stay in LegacyWorkerContextResourceLifecycle "
                        + "while WorkerManager remains the underlying storage facade:\n"
                        + String.join("\n", violations));
    }

    @Test
    void ruleBasedMatchingStrategyDoesNotReadWorkerContextStorageDirectly() throws IOException {
        Path strategyPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/strategy/RuleBasedTaskWorkerMatchingStrategy.java");
        String source = Files.readString(strategyPath, StandardCharsets.UTF_8);

        assertTrue(!Pattern.compile("\\bgetWorkerContextsByWorkerIds\\s*\\(").matcher(source).find(),
                "RuleBasedTaskWorkerMatchingStrategy must consume WorkerSchedulingCandidateEnumerator "
                        + "instead of directly reading WorkerContext storage");
    }

    @Test
    void ruleBasedMatchingStrategyDoesNotOwnRuleContextSnapshotFields() throws IOException {
        Path strategyPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/strategy/RuleBasedTaskWorkerMatchingStrategy.java");
        String source = Files.readString(strategyPath, StandardCharsets.UTF_8);

        List<String> violations = new ArrayList<>();
        if (Pattern.compile("\\bbuildPrefilterContextSnapshot\\b").matcher(source).find()) {
            violations.add(strategyPath + " defines a prefilter snapshot field builder");
        }
        if (Pattern.compile("\\.put\\s*\\(\\s*\"(?:workerScheduling|workerContext|taskUsesEventCapability|matchesTargetWorker)")
                .matcher(source)
                .find()) {
            violations.add(strategyPath + " manually writes rule/snapshot read-model fields");
        }

        assertTrue(violations.isEmpty(),
                "RuleBasedTaskWorkerMatchingStrategy must consume WorkerMatchContext for rule and "
                        + "diagnostic snapshot fields instead of owning a duplicate field map:\n"
                        + String.join("\n", violations));
    }

    @Test
    void strategyPackageWorkerContextImportStaysIsolatedToCandidateEnumerator() throws IOException {
        Path strategyRoot = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/strategy");
        Path allowedEnumerator = strategyRoot.resolve("WorkerSchedulingCandidateEnumerator.java");
        Pattern workerContextImport = Pattern.compile(
                "\\bimport\\s+com\\.xa\\.mass\\.base\\.model\\.WorkerContext\\s*;");
        Pattern workerContextStorageRead = Pattern.compile("\\bgetWorkerContextsByWorkerIds\\s*\\(");
        Pattern workerContextPayloadRead = Pattern.compile("\\.getWorkerContext\\s*\\(");

        List<String> violations = new ArrayList<>();
        for (Path path : javaSourceFiles(strategyRoot)) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            boolean allowed = path.equals(allowedEnumerator);
            if (!allowed && workerContextImport.matcher(source).find()) {
                violations.add(path + " imports WorkerContext outside the transitional candidate enumerator");
            }
            if (!allowed && workerContextStorageRead.matcher(source).find()) {
                violations.add(path + " reads WorkerContext storage outside the transitional candidate enumerator");
            }
            if (!allowed && workerContextPayloadRead.matcher(source).find()) {
                violations.add(path + " unwraps legacy WorkerContext payload outside the transitional candidate enumerator");
            }
        }

        assertTrue(violations.isEmpty(),
                "Matching strategy code must stay scheduling-candidate/view first. "
                        + "The only allowed matching-side WorkerContext expansion point is "
                        + "WorkerSchedulingCandidateEnumerator until legacy context-backed matching is retired:\n"
                        + String.join("\n", violations));
    }

    @Test
    void ruleBasedMatchingStrategyContextFixturesStayExplicitlyLegacy() throws IOException {
        Path strategyTestPath = TEST_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/strategy/RuleBasedTaskWorkerMatchingStrategyTest.java");
        List<String> violations = testMethodsCalling(strategyTestPath, "addWorkerContext(").stream()
                .distinct()
                .filter(methodName -> !methodName.startsWith("legacyContext"))
                .map(methodName -> strategyTestPath + " registers WorkerContext in non-legacy test: " + methodName)
                .toList();

        assertTrue(violations.isEmpty(),
                "RuleBasedTaskWorkerMatchingStrategyTest should prove normal matching with stateless "
                        + "worker scheduling attributes. Context-backed fixtures must be explicitly named "
                        + "legacyContext* until WorkerContext retirement removes them:\n"
                        + String.join("\n", violations));
    }

    @Test
    void retiredContextFirstSchedulingTypesStayRemoved() throws IOException {
        Map<String, Pattern> forbiddenPatterns = Map.ofEntries(
                Map.entry("MatchedWorkerContext", Pattern.compile("\\bMatchedWorkerContext\\b")),
                Map.entry("WorkerContextAllocator", Pattern.compile("\\bWorkerContextAllocator\\b"))
        );

        List<String> violations = new ArrayList<>();
        for (Path root : List.of(MAIN_SOURCE_ROOT, TEST_SOURCE_ROOT)) {
            for (Path path : javaSourceFiles(root.resolve("com/xa/mass/engine"))) {
                if (path.endsWith(Path.of("EngineSchedulingCoreArchitectureGuardTest.java"))) {
                    continue;
                }
                String source = Files.readString(path, StandardCharsets.UTF_8);
                for (Map.Entry<String, Pattern> forbiddenPattern : forbiddenPatterns.entrySet()) {
                    if (forbiddenPattern.getValue().matcher(source).find()) {
                        violations.add(path + " references retired scheduling handoff type: "
                                + forbiddenPattern.getKey());
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Engine scheduling must stay on WorkerSchedulingCandidate handoff; "
                        + "do not reintroduce the retired context-first types:\n"
                        + String.join("\n", violations));
    }

    private static List<Path> selectedSuiteSourceFiles() {
        SelectClasses selectedClasses = EngineSchedulingCoreSuite.class.getAnnotation(SelectClasses.class);
        assertNotNull(selectedClasses, "EngineSchedulingCoreSuite must declare @SelectClasses");
        List<Path> sourceFiles = Arrays.stream(selectedClasses.value())
                .filter(Objects::nonNull)
                .filter(testClass -> testClass != EngineSchedulingCoreArchitectureGuardTest.class)
                .map(EngineSchedulingCoreArchitectureGuardTest::sourcePathFor)
                .toList();
        assertTrue(!sourceFiles.isEmpty(), "EngineSchedulingCoreSuite must select at least one guarded test class");
        return sourceFiles;
    }

    private static Path sourcePathFor(Class<?> testClass) {
        String relativeSourcePath = testClass.getName().replace('.', '/') + ".java";
        return TEST_SOURCE_ROOT.resolve(relativeSourcePath);
    }

    private static List<Path> javaSourceFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    private static List<String> testMethodsCalling(Path sourcePath, String token) throws IOException {
        List<String> methods = new ArrayList<>();
        String currentTestMethod = null;
        boolean pendingTestMethod = false;
        Pattern testMethodPattern = Pattern.compile("\\s*void\\s+([A-Za-z0-9_]+)\\s*\\(");

        for (String line : Files.readAllLines(sourcePath, StandardCharsets.UTF_8)) {
            if (line.trim().equals("@Test")) {
                pendingTestMethod = true;
                currentTestMethod = null;
                continue;
            }
            if (pendingTestMethod) {
                java.util.regex.Matcher matcher = testMethodPattern.matcher(line);
                if (matcher.find()) {
                    currentTestMethod = matcher.group(1);
                    pendingTestMethod = false;
                }
            }
            if (line.contains(token) && currentTestMethod != null) {
                methods.add(currentTestMethod);
            }
        }

        return methods;
    }

    private static boolean isWorkerManager(Path path) {
        return path.endsWith(Path.of("com/xa/mass/engine/WorkerManager.java"));
    }

    private static boolean isLegacyWorkerContextLifecycle(Path path) {
        return path.endsWith(Path.of("com/xa/mass/engine/resource/LegacyWorkerContextResourceLifecycle.java"));
    }
}
