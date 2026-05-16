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
                        + "workerContext* rule variables are retired from the scheduling proof surface:\n"
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
    void workerContextStateMutationStaysOutOfEngineMainline() throws IOException {
        Map<String, Pattern> forbiddenPatterns = Map.ofEntries(
                Map.entry("getWorkerContextById", Pattern.compile("\\bgetWorkerContextById\\s*\\(")),
                Map.entry("updateWorkerContextById", Pattern.compile("\\bupdateWorkerContextById\\s*\\(")),
                Map.entry("deleteWorkerContextById", Pattern.compile("\\bdeleteWorkerContextById\\s*\\(")),
                Map.entry("addWorkerContext", Pattern.compile("\\baddWorkerContext\\s*\\(")),
                Map.entry("bindToTask", Pattern.compile("\\.bindToTask\\s*\\(")),
                Map.entry("startOccupying", Pattern.compile("\\.startOccupying\\s*\\(")),
                Map.entry("workerContextStatusTransition", Pattern.compile("\\bworkerContextStatusTransition\\s*\\("))
        );

        List<String> violations = new ArrayList<>();
        for (Path path : javaSourceFiles(MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine"))) {
            if (isWorkerManager(path)) {
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
                "WorkerContext runtime state mutation is retired from the engine mainline. "
                        + "WorkerManager remains a transitional storage facade only:\n"
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
    void strategyPackageDoesNotUseWorkerContextStorageOrPayloads() throws IOException {
        Path strategyRoot = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/strategy");
        Pattern workerContextImport = Pattern.compile(
                "\\bimport\\s+com\\.xa\\.mass\\.base\\.model\\.WorkerContext\\s*;");
        Pattern workerContextStorageRead = Pattern.compile("\\bgetWorkerContextsByWorkerIds\\s*\\(");
        Pattern workerContextPayloadRead = Pattern.compile("\\.getWorkerContext\\s*\\(");

        List<String> violations = new ArrayList<>();
        for (Path path : javaSourceFiles(strategyRoot)) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            if (workerContextImport.matcher(source).find()) {
                violations.add(path + " imports WorkerContext");
            }
            if (workerContextStorageRead.matcher(source).find()) {
                violations.add(path + " reads WorkerContext storage");
            }
            if (workerContextPayloadRead.matcher(source).find()) {
                violations.add(path + " unwraps legacy WorkerContext payload");
            }
        }

        assertTrue(violations.isEmpty(),
                "Matching strategy code must stay scheduling-candidate/view first. "
                        + "WorkerContext storage expansion is retired from the matching package:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerSchedulingCandidateDoesNotCarryWorkerContextPayload() throws IOException {
        Path candidatePath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/model/WorkerSchedulingCandidate.java");
        String source = Files.readString(candidatePath, StandardCharsets.UTF_8);

        List<String> violations = new ArrayList<>();
        if (Pattern.compile("\\bimport\\s+com\\.xa\\.mass\\.base\\.model\\.WorkerContext\\s*;")
                .matcher(source)
                .find()) {
            violations.add(candidatePath + " imports WorkerContext");
        }
        if (Pattern.compile("\\bWorkerContext\\s+workerContext\\b").matcher(source).find()) {
            violations.add(candidatePath + " stores WorkerContext payload");
        }
        if (Pattern.compile("\\bgetWorkerContext\\s*\\(").matcher(source).find()) {
            violations.add(candidatePath + " exposes WorkerContext payload accessor");
        }

        assertTrue(violations.isEmpty(),
                "WorkerSchedulingCandidate is a worker-level scheduling handoff. "
                        + "Legacy context identity may remain on WorkerSchedulingView, but the candidate "
                        + "must not carry a WorkerContext object:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerSchedulingViewDoesNotFlattenWorkerContextSchedulingFacts() throws IOException {
        Path viewPath = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/model/WorkerSchedulingView.java");
        String source = Files.readString(viewPath, StandardCharsets.UTF_8);

        Map<String, Pattern> forbiddenPatterns = Map.ofEntries(
                Map.entry("WorkerContextStatus", Pattern.compile("\\bWorkerContextStatus\\b")),
                Map.entry("workerContextProject", Pattern.compile("\\bworkerContextProject\\b")),
                Map.entry("workerContextRoutingTags", Pattern.compile("\\bworkerContextRoutingTags\\b")),
                Map.entry("workerContextAttributes", Pattern.compile("\\bworkerContextAttributes\\b")),
                Map.entry("workerContextAllocatable", Pattern.compile("\\bworkerContextAllocatable\\b")),
                Map.entry("workerContextAvailable", Pattern.compile("\\bworkerContextAvailable\\b")),
                Map.entry("workerContextUsable", Pattern.compile("\\bworkerContextUsable\\b")),
                Map.entry("workerContextReserved", Pattern.compile("\\bworkerContextReserved\\b")),
                Map.entry("workerContextOccupied", Pattern.compile("\\bworkerContextOccupied\\b"))
        );

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Pattern> forbiddenPattern : forbiddenPatterns.entrySet()) {
            if (forbiddenPattern.getValue().matcher(source).find()) {
                violations.add(viewPath + " flattens retired WorkerContext scheduling fact: "
                        + forbiddenPattern.getKey());
            }
        }

        assertTrue(violations.isEmpty(),
                "WorkerSchedulingView may retain legacy workerContextId compatibility identity, "
                        + "but scheduling facts must come from worker-level state and attributes:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerMatchContextDoesNotExposeWorkerContextRuleFields() throws IOException {
        Path contextPath = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/model/WorkerMatchContext.java");
        String source = Files.readString(contextPath, StandardCharsets.UTF_8);

        Map<String, Pattern> forbiddenPatterns = Map.ofEntries(
                Map.entry("workerContext field",
                        Pattern.compile("\\.put\\s*\\(\\s*\"workerContext[A-Za-z0-9_]*\"")),
                Map.entry("isWorkerContext field",
                        Pattern.compile("\\.put\\s*\\(\\s*\"isWorkerContext[A-Za-z0-9_]*\"")),
                Map.entry("hasWorkerContext field",
                        Pattern.compile("\\.put\\s*\\(\\s*\"hasWorkerContext\""))
        );

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Pattern> forbiddenPattern : forbiddenPatterns.entrySet()) {
            if (forbiddenPattern.getValue().matcher(source).find()) {
                violations.add(contextPath + " exposes retired rule context key: "
                        + forbiddenPattern.getKey());
            }
        }

        assertTrue(violations.isEmpty(),
                "WorkerMatchContext rule fields must stay workerScheduling*/worker-level. "
                        + "Legacy workerContext* variables are retired from the scheduling rule surface:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerSchedulingResourcePresenceDoesNotDependOnWorkerContextPresence() throws IOException {
        Path contextPath = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/model/WorkerMatchContext.java");
        String source = Files.readString(contextPath, StandardCharsets.UTF_8);

        Pattern contextBackedSchedulingResourceFlag = Pattern.compile(
                "\\.put\\s*\\(\\s*\"hasWorkerSchedulingResource\"\\s*,\\s*schedulingView\\.hasWorkerContext\\s*\\(");

        assertTrue(!contextBackedSchedulingResourceFlag.matcher(source).find(),
                "hasWorkerSchedulingResource must describe the worker-level scheduling resource, "
                        + "not legacy WorkerContext presence.");
    }

    @Test
    void assignmentDiagnosticsDoNotSnapshotWorkerContextLifecycle() throws IOException {
        Path engineRoot = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine");
        Pattern workerContextSnapshot = Pattern.compile("\\bWorkerContextSnapshot\\b");
        Pattern workerContextSnapshotAccessor = Pattern.compile("\\b(?:get|set)WorkerContextSnapshot\\s*\\(");

        List<String> violations = new ArrayList<>();
        for (Path path : javaSourceFiles(engineRoot)) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            if (workerContextSnapshot.matcher(source).find()) {
                violations.add(path + " references WorkerContextSnapshot");
            }
            if (workerContextSnapshotAccessor.matcher(source).find()) {
                violations.add(path + " reads or writes WorkerContextSnapshot on assignment diagnostics");
            }
        }

        assertTrue(violations.isEmpty(),
                "Assignment diagnostics must snapshot WorkerSchedulingView evidence. "
                        + "Legacy workerContextId may remain as payload identity, but WorkerContext lifecycle "
                        + "snapshots must not return to engine diagnostics:\n"
                        + String.join("\n", violations));
    }

    @Test
    void ruleBasedMatchingStrategyTestsDoNotRegisterWorkerContextFixtures() throws IOException {
        Path strategyTestPath = TEST_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/strategy/RuleBasedTaskWorkerMatchingStrategyTest.java");
        List<String> violations = testMethodsCalling(strategyTestPath, "addWorkerContext(").stream()
                .distinct()
                .map(methodName -> strategyTestPath + " registers WorkerContext in strategy test: " + methodName)
                .toList();

        assertTrue(violations.isEmpty(),
                "RuleBasedTaskWorkerMatchingStrategyTest should prove normal matching with stateless "
                        + "worker scheduling attributes. Context-backed matching fixtures are retired from "
                        + "the strategy proof surface:\n"
                        + String.join("\n", violations));
    }

    @Test
    void retiredContextFirstSchedulingTypesStayRemoved() throws IOException {
        Map<String, Pattern> forbiddenPatterns = Map.ofEntries(
                Map.entry("MatchedWorkerContext", Pattern.compile("\\bMatchedWorkerContext\\b")),
                Map.entry("WorkerContextAllocator", Pattern.compile("\\bWorkerContextAllocator\\b")),
                Map.entry("LegacyWorkerContextResourceLifecycle",
                        Pattern.compile("\\bLegacyWorkerContextResourceLifecycle\\b"))
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

}
