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

import static org.junit.jupiter.api.Assertions.assertFalse;
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
                Map.entry("releaseWorkerExclusiveLease", Pattern.compile("\\breleaseWorkerExclusiveLease\\s*\\(")),
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
                        + "WorkerManager must stay worker-level and must not expose context CRUD:\n"
                        + String.join("\n", violations));
    }

    @Test
    void engineMainlineDoesNotImportWorkerContextModel() throws IOException {
        Pattern workerContextImport = Pattern.compile(
                "\\bimport\\s+com\\.xa\\.mass\\.base\\.model\\.WorkerContext\\s*;");
        Pattern workerContextStatusImport = Pattern.compile(
                "\\bimport\\s+com\\.xa\\.mass\\.base\\.enums\\.worker\\.WorkerContextStatus\\s*;");

        List<String> violations = new ArrayList<>();
        for (Path path : javaSourceFiles(MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine"))) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            if (workerContextImport.matcher(source).find()) {
                violations.add(path + " imports WorkerContext");
            }
            if (workerContextStatusImport.matcher(source).find()) {
                violations.add(path + " imports WorkerContextStatus");
            }
        }

        assertTrue(violations.isEmpty(),
                "Engine production code must not depend on WorkerContext model classes. "
                        + "Context compatibility belongs outside the scheduling kernel:\n"
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
                        + "It must not carry a WorkerContext object:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerSchedulingViewDoesNotReadWorkerContextModel() throws IOException {
        Path viewPath = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/model/WorkerSchedulingView.java");
        String source = Files.readString(viewPath, StandardCharsets.UTF_8);

        Map<String, Pattern> forbiddenPatterns = Map.ofEntries(
                Map.entry("WorkerContext import",
                        Pattern.compile("\\bimport\\s+com\\.xa\\.mass\\.base\\.model\\.WorkerContext\\s*;")),
                Map.entry("WorkerContext parameter", Pattern.compile("\\bWorkerContext\\s+workerContext\\b")),
                Map.entry("hasWorkerContext", Pattern.compile("\\bhasWorkerContext\\b")),
                Map.entry("workerContextId accessor", Pattern.compile("\\bworkerContextId\\s*\\(")),
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
                violations.add(viewPath + " reads retired WorkerContext scheduling fact: "
                        + forbiddenPattern.getKey());
            }
        }

        assertTrue(violations.isEmpty(),
                "WorkerSchedulingView is a worker-level scheduling read model. It must not "
                        + "read account-slot identity or lifecycle state:\n"
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
    void dispatchResourcePolicyDoesNotModelWorkerContextAsResourceUsage() throws IOException {
        Path usagePath = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/resource/WorkerDispatchResourceUsage.java");
        Path policyPath = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/resource/DefaultWorkerDispatchResourcePolicy.java");
        Path binderPath = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/listener/SimpleTaskDispatchBinder.java");
        String usageSource = Files.readString(usagePath, StandardCharsets.UTF_8);
        String policySource = Files.readString(policyPath, StandardCharsets.UTF_8);
        String binderSource = Files.readString(binderPath, StandardCharsets.UTF_8);

        List<String> violations = new ArrayList<>();
        if (Pattern.compile("\\blegacyWorkerContextResource\\b|\\bstatelessWorkerResource\\b")
                .matcher(usageSource).find()) {
            violations.add(usagePath + " models WorkerContext as dispatch resource usage");
        }
        if (Pattern.compile("\\.hasWorkerContext\\s*\\(|\\.getWorkerContextId\\s*\\(")
                .matcher(policySource).find()) {
            violations.add(policyPath + " derives resource usage from WorkerContext identity");
        }
        if (Pattern.compile("new\\s+WorkerClaimTarget\\s*\\([^;]*getWorkerContextId\\s*\\(",
                Pattern.DOTALL).matcher(binderSource).find()) {
            violations.add(binderPath + " passes candidate WorkerContext identity into runtime claim target");
        }
        if (Pattern.compile("new\\s+WorkerClaimTarget\\s*\\(", Pattern.DOTALL).matcher(binderSource).find()) {
            violations.add(binderPath + " should use WorkerClaimTarget.workerLevel(...) for scheduling claims");
        }
        if (Pattern.compile("private\\s+String\\s+workerContextId\\s*\\(\\s*\\)", Pattern.DOTALL).matcher(binderSource).find()) {
            violations.add(binderPath + " keeps a workerContextId dispatch-slot accessor in the scheduling path");
        }
        if (Pattern.compile("TaskWorkAttemptIdSupport\\.runtimeAttemptId\\s*\\([^;]*workerContextId\\s*\\(",
                Pattern.DOTALL).matcher(binderSource).find()) {
            violations.add(binderPath + " feeds workerContextId into worker-level attempt id generation");
        }

        assertTrue(violations.isEmpty(),
                "Dispatch resource policy is worker/load based. Account-slot identity must not "
                        + "define resource usage:\n"
                        + String.join("\n", violations));
    }

    @Test
    void attemptResourceCleanupDoesNotTakeWorkerContextIdentityAsPolicyInput() throws IOException {
        Path policyPath = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/resource/WorkerDispatchResourcePolicy.java");
        Path releaserPath = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/resource/WorkerDispatchResourceReleaser.java");
        Path listenerPath = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/listener/TaskResourceReleaseListener.java");
        String policySource = Files.readString(policyPath, StandardCharsets.UTF_8);
        String releaserSource = Files.readString(releaserPath, StandardCharsets.UTF_8);
        String listenerSource = Files.readString(listenerPath, StandardCharsets.UTF_8);

        List<String> violations = new ArrayList<>();
        if (Pattern.compile("\\busageForAttempt\\s*\\(\\s*Task\\s+task\\s*,\\s*String\\s+workerContextId")
                .matcher(policySource)
                .find()) {
            violations.add(policyPath + " accepts workerContextId as attempt resource-policy input");
        }
        if (Pattern.compile("\\breleaseAttemptLockIfExclusive\\s*\\([^)]*workerContextId", Pattern.DOTALL)
                .matcher(releaserSource)
                .find()) {
            violations.add(releaserPath + " accepts workerContextId for attempt lock release");
        }
        if (Pattern.compile("\\bexclusiveAttemptContextByWorkerId\\b|\\.usageForAttempt\\s*\\([^;]*workerContextId",
                Pattern.DOTALL).matcher(listenerSource).find()) {
            violations.add(listenerPath + " keeps workerContextId in attempt cleanup policy flow");
        }

        assertTrue(violations.isEmpty(),
                "Attempt resource cleanup is worker/task based. Release policy must not depend "
                        + "on account-slot identity:\n"
                        + String.join("\n", violations));
    }

    @Test
    void resultCorrelationConstructionUsesNamedRuntimeSemantics() throws IOException {
        Path repoRoot = repositoryRoot();
        Path correlationPath = repoRoot.resolve(
                "xa-mass-base/src/main/java/com/xa/mass/base/runtime/result/TaskResultCorrelation.java")
                .normalize();
        List<Path> roots = List.of(
                repoRoot.resolve("xa-mass-base/src/main/java"),
                repoRoot.resolve("xa-mass-base/src/test/java"),
                repoRoot.resolve("xa-mass-engine/src/main/java"),
                repoRoot.resolve("xa-mass-engine/src/test/java"),
                repoRoot.resolve("transport/transport_runtime/src/main/java"),
                repoRoot.resolve("transport/transport_runtime/src/test/java")
        );
        Pattern directConstructor = Pattern.compile("\\bnew\\s+TaskResultCorrelation\\s*\\(");

        List<String> violations = new ArrayList<>();
        for (Path root : roots) {
            for (Path path : javaSourceFiles(root)) {
                Path normalizedPath = path.toAbsolutePath().normalize();
                if (normalizedPath.equals(correlationPath)) {
                    continue;
                }
                String source = Files.readString(path, StandardCharsets.UTF_8);
                if (directConstructor.matcher(source).find()) {
                    violations.add(normalizedPath
                            + " directly constructs TaskResultCorrelation instead of using "
                            + "workerLevel(...) or noActiveLease(...)");
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Result correlation must make worker-level vs legacy-context semantics explicit. "
                        + "Use named factories instead of direct record construction:\n"
                        + String.join("\n", violations));
    }

    @Test
    void resultRuntimeDraftConstructionUsesWorkerLevelFactories() throws IOException {
        Path repoRoot = repositoryRoot();
        List<Path> allowedFiles = List.of(
                repoRoot.resolve("platform_infra/mass-runtime-api/src/main/java/com/xa/mass/runtime/api/TaskResultCallbackDraft.java")
                        .normalize(),
                repoRoot.resolve("platform_infra/mass-runtime-api/src/main/java/com/xa/mass/runtime/api/TaskResultFinalDraft.java")
                        .normalize()
        );
        List<Path> roots = List.of(
                repoRoot.resolve("platform_infra/mass-runtime-api/src/main/java"),
                repoRoot.resolve("platform_infra/mass-runtime-api/src/test/java"),
                repoRoot.resolve("platform_infra/mass-runtime-memory/src/main/java"),
                repoRoot.resolve("platform_infra/mass-runtime-memory/src/test/java"),
                repoRoot.resolve("platform_infra/mass-runtime-redis/src/main/java"),
                repoRoot.resolve("platform_infra/mass-runtime-redis/src/test/java"),
                repoRoot.resolve("xa-mass-engine/src/main/java"),
                repoRoot.resolve("xa-mass-engine/src/test/java")
        );
        Pattern directConstructor = Pattern.compile("\\bnew\\s+TaskResult(?:Callback|Final)Draft\\s*\\(");

        List<String> violations = new ArrayList<>();
        for (Path root : roots) {
            for (Path path : javaSourceFiles(root)) {
                Path normalizedPath = path.toAbsolutePath().normalize();
                if (allowedFiles.contains(normalizedPath)) {
                    continue;
                }
                String source = Files.readString(path, StandardCharsets.UTF_8);
                if (directConstructor.matcher(source).find()) {
                    violations.add(normalizedPath
                            + " directly constructs TaskResult*Draft instead of using "
                            + "workerLevel(...)");
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Result runtime drafts must use worker-level payload semantics through named factories:\n"
                        + String.join("\n", violations));
    }

    @Test
    void dispatchBindingConstructionUsesWorkerLevelFactory() throws IOException {
        Path repoRoot = repositoryRoot();
        Path bindingPath = repoRoot.resolve(
                "xa-mass-base/src/main/java/com/xa/mass/base/runtime/dispatch/TaskDispatchBinding.java")
                .normalize();
        List<Path> roots = List.of(
                repoRoot.resolve("xa-mass-engine/src/main/java"),
                repoRoot.resolve("transport/transport_runtime/src/main/java"),
                repoRoot.resolve("transport/transport_api/src/main/java")
        );
        Pattern directConstructor = Pattern.compile("\\bnew\\s+TaskDispatchBinding\\s*\\(");

        List<String> violations = new ArrayList<>();
        for (Path root : roots) {
            for (Path path : javaSourceFiles(root)) {
                Path normalizedPath = path.toAbsolutePath().normalize();
                if (normalizedPath.equals(bindingPath)) {
                    continue;
                }
                String source = Files.readString(path, StandardCharsets.UTF_8);
                if (directConstructor.matcher(source).find()) {
                    violations.add(normalizedPath
                            + " directly constructs TaskDispatchBinding instead of using "
                            + "workerLevel(...)");
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Dispatch binding must use worker-level payload semantics through named factories:\n"
                        + String.join("\n", violations));
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
                        + "WorkerContext lifecycle snapshots must not return to engine diagnostics:\n"
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

    @Test
    void retiredWorkerSelectorPathStaysRemoved() throws IOException {
        Pattern workerSelector = Pattern.compile("\\b(?:Default)?WorkerSelector\\b");

        List<String> violations = new ArrayList<>();
        for (Path path : javaSourceFiles(MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine"))) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            if (workerSelector.matcher(source).find()) {
                violations.add(path + " references the retired parallel worker selector path");
            }
        }

        assertTrue(violations.isEmpty(),
                "Worker selection must stay on the active candidate-source -> rule/rank -> "
                        + "allocation/resource-admission mainline. Do not reintroduce the unused "
                        + "WorkerSelector path:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerAccessTypesStayInWorkerPackage() throws IOException {
        Map<Path, String> retiredRootTypes = Map.ofEntries(
                Map.entry(MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/WorkerManager.java"), "WorkerManager"),
                Map.entry(MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/WorkerReachabilityState.java"),
                        "WorkerReachabilityState"),
                Map.entry(MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/WorkerReachabilityView.java"),
                        "WorkerReachabilityView")
        );
        Map<String, Pattern> retiredImports = Map.ofEntries(
                Map.entry("WorkerManager",
                        Pattern.compile("\\bimport\\s+com\\.xa\\.mass\\.engine\\.WorkerManager\\s*;")),
                Map.entry("WorkerReachabilityState",
                        Pattern.compile("\\bimport\\s+com\\.xa\\.mass\\.engine\\.WorkerReachabilityState\\s*;")),
                Map.entry("WorkerReachabilityView",
                        Pattern.compile("\\bimport\\s+com\\.xa\\.mass\\.engine\\.WorkerReachabilityView\\s*;"))
        );

        List<String> violations = new ArrayList<>();
        for (Map.Entry<Path, String> retiredRootType : retiredRootTypes.entrySet()) {
            if (Files.exists(retiredRootType.getKey())) {
                violations.add(retiredRootType.getKey() + " keeps worker access type in root engine package: "
                        + retiredRootType.getValue());
            }
        }

        for (Path root : List.of(MAIN_SOURCE_ROOT, TEST_SOURCE_ROOT)) {
            for (Path path : javaSourceFiles(root)) {
                String source = Files.readString(path, StandardCharsets.UTF_8);
                for (Map.Entry<String, Pattern> retiredImport : retiredImports.entrySet()) {
                    if (retiredImport.getValue().matcher(source).find()) {
                        violations.add(path + " imports retired root-package worker access type: "
                                + retiredImport.getKey());
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Worker access/read-view types belong in com.xa.mass.engine.worker. "
                        + "Do not move WorkerManager or reachability types back into the root engine package:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerGroupSnapshotDoesNotReadWorkerLevelCapabilityTruth() throws IOException {
        Path snapshotPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/worker/WorkerRegistrySnapshot.java");
        String source = Files.readString(snapshotPath, StandardCharsets.UTF_8);

        Map<String, Pattern> forbiddenPatterns = Map.ofEntries(
                Map.entry("supportedProjects", Pattern.compile("\\.getSupportedProjects\\s*\\(")),
                Map.entry("supportedEventCodes", Pattern.compile("\\.getSupportedEventCodes\\s*\\("))
        );

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Pattern> forbiddenPattern : forbiddenPatterns.entrySet()) {
            if (forbiddenPattern.getValue().matcher(source).find()) {
                violations.add(snapshotPath + " reads worker-level capability truth: "
                        + forbiddenPattern.getKey());
            }
        }

        assertTrue(violations.isEmpty(),
                "WorkerRegistrySnapshot indexes WorkerGroup EventBinding/EventKey truth only. "
                        + "Worker-level supportedProjects/supportedEventCodes remain compatibility/read "
                        + "surfaces and must not drive WG candidate-source indexes:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerManagerPublishesRegistrySnapshotThroughCapabilityAuthority() throws IOException {
        Path workerManagerPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/worker/WorkerManager.java");
        String source = Files.readString(workerManagerPath, StandardCharsets.UTF_8);

        List<String> violations = new ArrayList<>();
        if (!Pattern.compile("\\bWorkerCapabilityAuthority\\b").matcher(source).find()) {
            violations.add(workerManagerPath + " does not hold the WorkerCapabilityAuthority owner");
        }
        if (!Pattern.compile("\\bcapabilityAuthority\\.composeSnapshot\\s*\\(").matcher(source).find()) {
            violations.add(workerManagerPath + " does not publish snapshots through WorkerCapabilityAuthority");
        }

        assertTrue(violations.isEmpty(),
                "WorkerManager owns active snapshot publication, but effective capability composition "
                        + "must flow through WorkerCapabilityAuthority:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerGroupCompatibilityProjectionIsRetired() throws IOException {
        Path workerPackage = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/worker");
        Pattern projectionReference = Pattern.compile("\\bWorkerGroupCompatibilityProjection\\b");

        List<String> violations = new ArrayList<>();
        for (Path path : javaSourceFiles(workerPackage)) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            if (projectionReference.matcher(source).find()) {
                violations.add(path + " references retired WorkerGroupCompatibilityProjection");
            }
        }

        assertTrue(violations.isEmpty(),
                "WorkerGroupCompatibilityProjection is retired. WorkerGroup declarations are "
                        + "the only capability truth for candidate-source indexes:\n"
                        + String.join("\n", violations));
    }

    @Test
    void adapterNodeAndNodeGroupBindingDoNotOwnCapabilityTruth() throws IOException {
        Path workerPackage = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/worker");
        List<Path> ownerPaths = List.of(
                workerPackage.resolve("AdapterNodeRecord.java"),
                workerPackage.resolve("NodeGroupBindingRecord.java")
        );
        Map<String, Pattern> forbiddenPatterns = Map.ofEntries(
                Map.entry("eventBindings", Pattern.compile("\\beventBindings\\b")),
                Map.entry("eventCodes", Pattern.compile("\\beventCodes\\b")),
                Map.entry("EventBinding", Pattern.compile("\\bEventBinding\\b")),
                Map.entry("EventKey", Pattern.compile("\\bEventKey\\b")),
                Map.entry("WorkerCapability", Pattern.compile("\\bWorkerCapability"))
        );

        List<String> violations = new ArrayList<>();
        for (Path path : ownerPaths) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            for (Map.Entry<String, Pattern> forbiddenPattern : forbiddenPatterns.entrySet()) {
                if (forbiddenPattern.getValue().matcher(source).find()) {
                    violations.add(path + " owns capability truth token: " + forbiddenPattern.getKey());
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "AdapterNode and NodeGroupBinding are relation/diagnostic owners only. "
                        + "WorkerGroup remains the capability truth owner:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerGroupAdapterNodeRelationTruthIsRemoved() throws IOException {
        Path workerPackage = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/worker");
        Map<Path, Pattern> forbiddenPatterns = Map.of(
                workerPackage.resolve("WorkerGroupRecord.java"),
                Pattern.compile("\\badapterNodeId\\b"),
                workerPackage.resolve("WorkerRegistrySnapshot.java"),
                Pattern.compile("\\bgroupIdsByAdapterNodeId\\b")
        );

        List<String> violations = new ArrayList<>();
        for (Map.Entry<Path, Pattern> forbiddenPattern : forbiddenPatterns.entrySet()) {
            String source = Files.readString(forbiddenPattern.getKey(), StandardCharsets.UTF_8);
            if (forbiddenPattern.getValue().matcher(source).find()) {
                violations.add(forbiddenPattern.getKey() + " reintroduces group-owned node relation truth");
            }
        }

        assertTrue(violations.isEmpty(),
                "WorkerGroup no longer owns adapter-node relation truth. "
                        + "Use NodeGroupBinding for node/group relation queries:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerCandidateIndexStaysOnGroupCapabilityTruth() throws IOException {
        Path indexPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/worker/WorkerCandidateIndex.java");
        String source = Files.readString(indexPath, StandardCharsets.UTF_8);

        Map<String, Pattern> forbiddenPatterns = Map.ofEntries(
                Map.entry("supportedProjects", Pattern.compile("\\.getSupportedProjects\\s*\\(")),
                Map.entry("supportedEventCodes", Pattern.compile("\\.getSupportedEventCodes\\s*\\(")),
                Map.entry("AdapterNode", Pattern.compile("\\bAdapterNode")),
                Map.entry("NodeGroupBinding", Pattern.compile("\\bNodeGroupBinding")),
                Map.entry("WorkerManager", Pattern.compile("\\bWorkerManager\\b")),
                Map.entry("WorkerStorage", Pattern.compile("\\bWorkerStorage\\b")),
                Map.entry("unbounded group worker enumeration",
                        Pattern.compile("\\.workerIdsByGroupId\\s*\\(")),
                Map.entry("all-workers scan", Pattern.compile("\\.getAllWorkers\\s*\\("))
        );

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Pattern> forbiddenPattern : forbiddenPatterns.entrySet()) {
            if (forbiddenPattern.getValue().matcher(source).find()) {
                violations.add(indexPath + " leaks non-index candidate-source dependency: "
                        + forbiddenPattern.getKey());
            }
        }
        if (!Pattern.compile("\\bWorkerRegistry\\b").matcher(source).find()) {
            violations.add(indexPath + " does not use WorkerRegistry for bounded acquisition");
        }
        if (Pattern.compile("\\bWorkerRouteBucketOwner\\b").matcher(source).find()) {
            violations.add(indexPath + " still references removed WorkerRouteBucketOwner residue");
        }

        assertTrue(violations.isEmpty(),
                "WorkerCandidateIndex is Stage-1 group-capability narrowing only. "
                + "It must not read worker-level compatibility capability, WorkerManager, "
                + "storage, route-bucket residue, unbounded group worker enumeration, full scans, "
                        + "or Stage-2 runtime admission state:\n"
                + String.join("\n", violations));
    }

    @Test
    void workerStorageAllWorkerScanStaysOutOfSchedulingHotPath() throws IOException {
        Path engineRoot = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine");
        Path workerManagerPath = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/worker/WorkerManager.java");
        Pattern allWorkerScan = Pattern.compile("\\.getAllWorkers\\s*\\(");

        List<String> violations = new ArrayList<>();
        for (Path path : javaSourceFiles(engineRoot)) {
            if (path.equals(workerManagerPath)) {
                continue;
            }
            String source = Files.readString(path, StandardCharsets.UTF_8);
            if (allWorkerScan.matcher(source).find()) {
                violations.add(path + " calls WorkerStorage.getAllWorkers() outside WorkerManager convergence boundary");
            }
        }

        assertTrue(violations.isEmpty(),
                "WorkerStorage.getAllWorkers() is a current bootstrap/refresh residue, not a scheduling hot-path "
                        + "candidate source. New scheduling code must use WorkerManager/WorkerCandidateIndex "
                        + "until WorkerRegistry owns bounded acquisition:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerManagerCandidateReadPathDoesNotEnterRegistryLock() throws IOException {
        Path workerManagerPath = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/worker/WorkerManager.java");
        String source = Files.readString(workerManagerPath, StandardCharsets.UTF_8);
        Map<String, String> guardedMethods = Map.of(
                "findWorkerCandidates", "public List<Worker> findWorkerCandidates",
                "getWorkerCandidateIndex", "public WorkerCandidateIndex getWorkerCandidateIndex"
        );

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, String> method : guardedMethods.entrySet()) {
            String body = sourceMethod(source, method.getValue());
            if (Pattern.compile("\\bworkerRegistryLock\\b").matcher(body).find()) {
                violations.add(workerManagerPath + "#" + method.getKey() + " enters workerRegistryLock");
            }
            if (Pattern.compile("\\bsynchronized\\s*\\(").matcher(body).find()) {
                violations.add(workerManagerPath + "#" + method.getKey() + " enters synchronized block");
            }
        }

        assertTrue(violations.isEmpty(),
                "Worker candidate read path must not take WorkerManager's registry lock. "
                        + "WSR convergence allows stale/bounded candidate indexes and validates at Stage-2:\n"
                        + String.join("\n", violations));
    }

    @Test
    void matchingMainlineDoesNotOwnWorkerGroupIndexLookup() throws IOException {
        Path strategyRoot = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/strategy");
        Pattern workerRegistrySnapshot = Pattern.compile("\\bWorkerRegistrySnapshot\\b");
        Pattern workerCandidateIndex = Pattern.compile("\\bWorkerCandidateIndex\\b");
        Pattern directSnapshotAccessor = Pattern.compile("\\.getWorkerRegistrySnapshot\\s*\\(");

        List<String> violations = new ArrayList<>();
        for (Path path : javaSourceFiles(strategyRoot)) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            if (workerRegistrySnapshot.matcher(source).find()
                    || workerCandidateIndex.matcher(source).find()
                    || directSnapshotAccessor.matcher(source).find()) {
                violations.add(path + " owns WorkerGroup snapshot/index lookup");
            }
        }

        assertTrue(violations.isEmpty(),
                "Strategy code may materialize WorkerGroup capability already selected by WorkerManager, "
                        + "but WorkerManager/WorkerCandidateIndex must own Stage-1 snapshot/index lookup:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerManagerDoesNotOwnRouteBucketMembershipResidue() throws IOException {
        Path workerManagerPath = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/worker/WorkerManager.java");
        String source = Files.readString(workerManagerPath, StandardCharsets.UTF_8);

        assertFalse(source.contains("WorkerRouteBucketOwner"),
                "WorkerManager must not keep a second route bucket membership owner. "
                        + "Stage-1 candidate membership is owned by WorkerRegistry; "
                        + "snapshot-backed route bucket code may only remain as isolated residue until removed.");
    }

    @Test
    void workerManagerReadsWorkerMembershipFromRegistryNotSnapshot() throws IOException {
        Path workerManagerPath = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/worker/WorkerManager.java");
        String source = Files.readString(workerManagerPath, StandardCharsets.UTF_8);

        List<String> violations = new ArrayList<>();
        for (String forbidden : List.of(
                "workerRegistrySnapshot.workerIdsByGroupId",
                "workerRegistrySnapshot.workerIdsByAdapterNodeId",
                "workerRegistrySnapshot.workerIdsByAdapterNodeGroup")) {
            if (source.contains(forbidden)) {
                violations.add(workerManagerPath + " reads worker membership from snapshot: " + forbidden);
            }
        }

        assertTrue(violations.isEmpty(),
                "WorkerRegistry owns runtime worker membership. WorkerManager must not use "
                        + "WorkerRegistrySnapshot worker-id indexes for gate or cleanup mutation:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerRegistrySnapshotDoesNotOwnWorkerMembershipIndexes() throws IOException {
        Path snapshotPath = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/worker/WorkerRegistrySnapshot.java");
        String source = Files.readString(snapshotPath, StandardCharsets.UTF_8);

        List<String> violations = new ArrayList<>();
        for (String forbidden : List.of(
                "workerIdsByGroupId",
                "workerIdsByAdapterNodeId",
                "workerIdsByAdapterNodeGroup",
                "groupIdByWorkerId",
                "AdapterNodeGroupKey")) {
            if (source.contains(forbidden)) {
                violations.add(snapshotPath + " owns worker membership residue: " + forbidden);
            }
        }

        assertTrue(violations.isEmpty(),
                "WorkerRegistrySnapshot may retain group capability indexes and diagnostic worker rows, "
                        + "but runtime worker membership belongs to WorkerRegistry:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerManagerDoesNotOwnSecondWorkerRowCopy() throws IOException {
        Path workerManagerPath = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/worker/WorkerManager.java");
        String source = Files.readString(workerManagerPath, StandardCharsets.UTF_8);

        assertFalse(source.contains("workerRegistryRows"),
                "WorkerManager must not own a second mutable worker row map. "
                        + "WorkerStorage remains the current control-plane row source and "
                        + "WorkerRegistry owns runtime slot/index/admission truth.");
        assertFalse(source.contains("getWorkersByGroupId("),
                "WorkerManager must not expose storage-backed group worker scans. "
                        + "Scheduling candidate membership belongs to WorkerRegistry.");
    }

    @Test
    void workerSchedulingCandidateEnumeratorStaysPackagePrivateImplementationDetail() throws IOException {
        Path enumeratorPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/strategy/WorkerSchedulingCandidateEnumerator.java");
        String source = Files.readString(enumeratorPath, StandardCharsets.UTF_8);

        Map<String, Pattern> forbiddenPatterns = Map.ofEntries(
                Map.entry("public class",
                        Pattern.compile("\\bpublic\\s+(?:final\\s+)?class\\s+WorkerSchedulingCandidateEnumerator\\b")),
                Map.entry("public constructor",
                        Pattern.compile("\\bpublic\\s+WorkerSchedulingCandidateEnumerator\\s*\\(")),
                Map.entry("public enumerate",
                        Pattern.compile("\\bpublic\\s+List\\s*<\\s*WorkerSchedulingCandidate\\s*>\\s+enumerate\\s*\\("))
        );

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Pattern> forbiddenPattern : forbiddenPatterns.entrySet()) {
            if (forbiddenPattern.getValue().matcher(source).find()) {
                violations.add(enumeratorPath + " exposes " + forbiddenPattern.getKey());
            }
        }

        assertTrue(violations.isEmpty(),
                "WorkerSchedulingCandidateEnumerator must stay a strategy-package implementation detail. "
                        + "WG-3 should replace the candidate source with WorkerCandidateIndex rather than "
                        + "stabilizing this enumerator as a public extension point:\n"
                        + String.join("\n", violations));
    }

    @Test
    void ruleBasedMatchingStrategyDoesNotScanAllWorkersDirectly() throws IOException {
        Path strategyPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/strategy/RuleBasedTaskWorkerMatchingStrategy.java");
        String source = Files.readString(strategyPath, StandardCharsets.UTF_8);

        List<String> violations = new ArrayList<>();
        if (Pattern.compile("\\.getAllWorkers\\s*\\(").matcher(source).find()) {
            violations.add(strategyPath + " calls getAllWorkers() directly");
        }
        if (!Pattern.compile("\\.findWorkerCandidates\\s*\\(").matcher(source).find()) {
            violations.add(strategyPath + " does not consume WorkerManager.findWorkerCandidates(...)");
        }

        assertTrue(violations.isEmpty(),
                "RuleBasedTaskWorkerMatchingStrategy must consume the centralized candidate source. "
                        + "Do not reintroduce direct worker-pool scans in the rule/rank/resource path:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerManagerDoesNotUseWorkerLevelEventStorageIndexForEventCandidateSource() throws IOException {
        Path workerManagerPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/worker/WorkerManager.java");
        String source = Files.readString(workerManagerPath, StandardCharsets.UTF_8);

        List<String> violations = new ArrayList<>();
        if (Pattern.compile("\\.getWorkersBySupportedEventCode\\s*\\(").matcher(source).find()) {
            violations.add(workerManagerPath + " calls worker-level supported-event storage index");
        }
        if (Pattern.compile("\\.getWorkersBySupportedProject\\s*\\(").matcher(source).find()) {
            violations.add(workerManagerPath + " calls worker-level supported-project storage index");
        }
        if (!Pattern.compile("\\bgetWorkerCandidateIndex\\s*\\(\\)\\.workersFor\\s*\\(").matcher(source).find()) {
            violations.add(workerManagerPath + " does not use WorkerCandidateIndex for indexed candidate lookup");
        }

        assertTrue(violations.isEmpty(),
                "WG candidate source must flow through WorkerCandidateIndex for target, event, and project "
                        + "lookup. Do not reintroduce worker-level supportedProject/supportedEvent storage "
                        + "indexes as active scheduling candidate sources:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerStorageDoesNotExposeWorkerCapabilityCandidateLookup() throws IOException {
        Path workerStoragePath = repositoryRoot().resolve(
                "platform_infra/mass-storage-api/src/main/java/com/xa/mass/storage/api/WorkerStorage.java");
        String source = Files.readString(workerStoragePath, StandardCharsets.UTF_8);

        List<String> violations = new ArrayList<>();
        if (Pattern.compile("\\bgetWorkersBySupportedEventCode\\s*\\(").matcher(source).find()) {
            violations.add(workerStoragePath + " exposes supported-event candidate lookup");
        }
        if (Pattern.compile("\\bgetWorkersBySupportedProject\\s*\\(").matcher(source).find()) {
            violations.add(workerStoragePath + " exposes supported-project candidate lookup");
        }

        assertTrue(violations.isEmpty(),
                "WorkerStorage must stay runtime worker-registry storage. Capability candidate "
                        + "lookup belongs to WorkerRegistrySnapshot / WorkerCandidateIndex, not control-plane "
                + "storage APIs:\n"
                + String.join("\n", violations));
    }

    @Test
    void jdbcStorageDoesNotOwnWorkerRuntimeRegistry() throws IOException {
        Path jdbcSourceRoot = repositoryRoot().resolve(
                "platform_infra/mass-storage-jdbc/src/main/java/com/xa/mass/storage/jdbc");
        Path migrationPath = repositoryRoot().resolve(
                "platform_infra/mass-storage-jdbc/src/main/resources/db/migration/control-plane/V1__create_control_plane_tables.sql");

        List<String> violations = new ArrayList<>();
        if (Files.exists(jdbcSourceRoot.resolve("JdbcWorkerStorage.java"))) {
            violations.add(jdbcSourceRoot.resolve("JdbcWorkerStorage.java")
                    + " reintroduces DB-backed worker runtime storage");
        }
        if (Files.exists(jdbcSourceRoot.resolve("JdbcWorkerCompatibilityProjection.java"))) {
            violations.add(jdbcSourceRoot.resolve("JdbcWorkerCompatibilityProjection.java")
                    + " reintroduces a second worker runtime data structure under JDBC");
        }
        if (Files.exists(migrationPath)) {
            String migration = Files.readString(migrationPath, StandardCharsets.UTF_8);
            if (Pattern.compile("\\bxa_worker\\b").matcher(migration).find()) {
                violations.add(migrationPath + " creates xa_worker; worker registry is runtime/trace truth, not DB CRUD");
            }
        }

        assertTrue(violations.isEmpty(),
                "JDBC storage must not own worker runtime registry, worker locks, or worker attribute churn. "
                        + "Use the shared runtime worker backend and persist historical query needs through "
                        + "trace/audit ingestion:\n"
                        + String.join("\n", violations));
    }

    @Test
    void groupSelectorFirstCandidateSourceDoesNotUseEventOrAllWorkerFallback() throws IOException {
        Path candidateIndexPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/worker/WorkerCandidateIndex.java");
        Path workerManagerPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/worker/WorkerManager.java");
        Path binderPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/listener/SimpleTaskDispatchBinder.java");
        Path traceLoggerPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/util/TraceEventLogger.java");
        Path ruleConfigPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/rules/RuleConfig.java");
        Path assignmentListenerPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/listener/TaskWorkerAssignListener.java");
        Path allocationRequestPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/assignment/AssignmentAllocationRequest.java");

        String candidateIndex = Files.readString(candidateIndexPath, StandardCharsets.UTF_8);
        String findWorkerCandidates = sourceMethod(
                Files.readString(workerManagerPath, StandardCharsets.UTF_8),
                "public List<Worker> findWorkerCandidates"
        );
        String binder = Files.readString(binderPath, StandardCharsets.UTF_8);
        String traceLogger = Files.readString(traceLoggerPath, StandardCharsets.UTF_8);
        String ruleConfig = Files.readString(ruleConfigPath, StandardCharsets.UTF_8);
        String assignmentListener = Files.readString(assignmentListenerPath, StandardCharsets.UTF_8);
        String allocationRequest = Files.readString(allocationRequestPath, StandardCharsets.UTF_8);

        List<String> violations = new ArrayList<>();
        if (Pattern.compile("\\bTaskSharedConfig\\.sdkEventCode\\s*\\(").matcher(candidateIndex).find()) {
            violations.add(candidateIndexPath + " reads sdkEventCode in candidate-source lookup");
        }
        if (Pattern.compile("\\bgroupIdsByEventKey\\s*\\(").matcher(candidateIndex).find()) {
            violations.add(candidateIndexPath + " reads groupIdsByEventKey in candidate-source lookup");
        }
        if (Pattern.compile("\\bgroupIdsByProjectCode\\s*\\(").matcher(candidateIndex).find()) {
            violations.add(candidateIndexPath + " reads groupIdsByProjectCode in candidate-source lookup");
        }
        if (Pattern.compile("\\.getAllWorkers\\s*\\(").matcher(findWorkerCandidates).find()) {
            violations.add(workerManagerPath + "#findWorkerCandidates falls back to all workers");
        }
        for (String oldSource : List.of("GROUP_INDEX", "GROUP_PROJECT_INDEX", "ALL_WORKERS_FALLBACK")) {
            if (binder.contains(oldSource)) {
                violations.add(binderPath + " can emit old workerCandidateSource " + oldSource);
            }
            if (traceLogger.contains(oldSource)) {
                violations.add(traceLoggerPath + " can emit old workerCandidateSource " + oldSource);
            }
        }
        if (ruleConfig.contains("worker_capability_check") || ruleConfig.contains("supportsEvent")) {
            violations.add(ruleConfigPath + " keeps event/project capability as default eligibility truth");
        }
        if (assignmentListener.contains("usesTaskLevelEventCapability")
                || assignmentListener.contains("TaskSharedConfig.sdkEventCode(task)")) {
            violations.add(assignmentListenerPath + " uses task eventCode as assignment allocation truth");
        }
        if (Pattern.compile("\\bfindWorkerCandidates\\s*\\(").matcher(assignmentListener).find()) {
            violations.add(assignmentListenerPath + " pre-fetches Stage-1 candidates before matching");
        }
        if (allocationRequest.contains("taskLevelEventCapability")) {
            violations.add(allocationRequestPath + " exposes event capability allocation wording");
        }
        if (allocationRequest.contains("workerCandidateCount")
                || allocationRequest.contains("groupSelectorCandidateSource")) {
            violations.add(allocationRequestPath + " couples allocation planning to Stage-1 candidate source shape");
        }

        assertTrue(violations.isEmpty(),
                "Group-selector-first scheduling must not reintroduce event/project/all-worker "
                        + "candidate-source fallbacks:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerStorageDoesNotOwnRuntimeExclusiveLeaseTruth() throws IOException {
        Path workerStoragePath = Path.of("..", "platform_infra", "mass-storage-api", "src", "main", "java",
                "com", "xa", "mass", "storage", "api", "WorkerStorage.java");
        Path memoryStoragePath = Path.of("..", "platform_infra", "mass-storage-memory", "src", "main", "java",
                "com", "xa", "mass", "storage", "memory", "InMemoryWorkerStorage.java");
        Path sdkDiagnosticsPath = Path.of("..", "xa-mass-sdk", "src", "main", "java",
                "com", "xa", "mass", "sdk", "DefaultRuntimeDiagnosticsOperations.java");

        List<String> violations = new ArrayList<>();
        String workerStorage = Files.readString(workerStoragePath, StandardCharsets.UTF_8);
        for (String forbidden : List.of(
                "tryLockWorker",
                "unlockWorker",
                "isLocked",
                "getLockedWorkers",
                "tryAcquireWorkerExclusiveLease",
                "releaseWorkerExclusiveLease",
                "hasWorkerExclusiveLease",
                "getExclusiveLeaseWorkerIds")) {
            if (workerStorage.contains(forbidden)) {
                violations.add(workerStoragePath + " exposes runtime exclusive lease method: " + forbidden);
            }
        }

        String memoryStorage = Files.readString(memoryStoragePath, StandardCharsets.UTF_8);
        if (memoryStorage.contains("lockedWorkers")) {
            violations.add(memoryStoragePath + " keeps a separate lockedWorkers truth");
        }

        String sdkDiagnostics = Files.readString(sdkDiagnosticsPath, StandardCharsets.UTF_8);
        if (sdkDiagnostics.contains("getWorkerStorage().isLocked")) {
            violations.add(sdkDiagnosticsPath + " reads lock truth from WorkerStorage");
        }

        assertTrue(violations.isEmpty(),
                "WorkerStorage is control-plane worker row storage only. Runtime exclusive lease truth "
                        + "must stay in WorkerRegistry/WorkerManager:\n"
                        + String.join("\n", violations));
    }

    @Test
    void productionDoesNotWireWorkerLoadViewAsRuntimeOccupancyOwner() throws IOException {
        Path workerManagerPath = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/worker/WorkerManager.java");
        String source = Files.readString(workerManagerPath, StandardCharsets.UTF_8);

        List<String> violations = new ArrayList<>();
        if (source.contains("WorkerLoadView") || source.contains("workerLoadView")) {
            violations.add(workerManagerPath + " still wires WorkerLoadView");
        }
        for (String forbidden : List.of(
                ".tryReserveCapacity(",
                ".confirmReservation(",
                ".releaseReservation(",
                ".recordWorkClaimed(",
                ".recordWorkFinal(",
                ".recordDeclaredCapacity("
        )) {
            if (source.contains("workerLoadView" + forbidden)) {
                violations.add(workerManagerPath + " writes occupancy through WorkerLoadView: " + forbidden);
            }
        }
        Path workerLoadViewPath = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/load/WorkerLoadView.java");
        Path inMemoryWorkerLoadViewPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/load/InMemoryWorkerLoadView.java");
        if (Files.exists(workerLoadViewPath)) {
            violations.add(workerLoadViewPath + " still exists as a mutable occupancy owner");
        }
        if (Files.exists(inMemoryWorkerLoadViewPath)) {
            violations.add(inMemoryWorkerLoadViewPath + " still exists as a mutable occupancy owner");
        }
        for (Path sourcePath : List.of(
                Path.of("..", "xa-mass-sdk", "src", "main", "java", "com", "xa", "mass", "starter", "config",
                        "EngineConfig.java"),
                Path.of("..", "xa-mass-sdk", "src", "main", "java", "com", "xa", "mass", "starter", "builder",
                        "MassEngineBuilder.java")
        )) {
            String sdkSource = Files.readString(sourcePath, StandardCharsets.UTF_8);
            if (sdkSource.contains("WorkerLoadView") || sdkSource.contains("workerLoadView")) {
                violations.add(sourcePath + " still exposes WorkerLoadView production wiring");
            }
        }

        assertTrue(violations.isEmpty(),
                "WorkerRegistry is the production worker occupancy owner; WorkerLoadView "
                        + "must not remain as live production wiring:\n"
                        + String.join("\n", violations));
    }

    @Test
    void dispatchAvailabilityGateTruthBelongsToWorkerRegistry() throws IOException {
        List<String> violations = new ArrayList<>();
        Path retiredOwnerPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/worker/WorkerDispatchAvailabilityOwner.java");
        if (Files.exists(retiredOwnerPath)) {
            violations.add(retiredOwnerPath + " reintroduces an independent dispatch gate owner");
        }
        String workerManagerSource = Files.readString(
                MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/worker/WorkerManager.java"),
                StandardCharsets.UTF_8
        );
        if (!workerManagerSource.contains("workerRegistry.disableDispatch")
                || !workerManagerSource.contains("workerRegistry.clearDispatchDisable")) {
            violations.add("WorkerManager must route dispatch gate mutation to WorkerRegistry");
        }

        assertTrue(violations.isEmpty(),
                "Worker dispatch gate truth belongs to WorkerRegistry/WorkerSlot.disabledSources:\n"
                        + String.join("\n", violations));
    }

    @Test
    void taskWriteLockRemainsLifecycleAndProgressOnly() throws IOException {
        Path taskManagerPath = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/TaskManager.java");
        String source = Files.readString(taskManagerPath, StandardCharsets.UTF_8);

        List<String> approvedTaskWriteLockMethods = List.of(
                "public boolean deleteTask(String taskId)",
                "public boolean approveTask(String taskId)",
                "public boolean rejectTask(String taskId)",
                "public boolean blockTask(String taskId)",
                "public boolean pauseTask(String taskId)",
                "public TaskResumeResult resumeTaskDetailed(String taskId)",
                "public boolean cancelTask(String taskId)",
                "public boolean terminateTask(String taskId, TaskTerminalReason reason)",
                "public TaskAppendReceipt appendTaskItemsWithReceipt(String taskId, List<java.util.Map<String, Object>> items)",
                "public boolean sealTask(String taskId)",
                "public TaskStateResolutionResult resolveTaskState(String taskId)",
                "public TaskStateValidationResult validateTaskState(String taskId)",
                "TaskStateValidationResult auditTaskProjectionState(String taskId)",
                "<T> T withTaskLock(String taskId, Supplier<T> action)",
                "void withTaskLock(String taskId, Runnable action)"
        );
        String unapprovedSource = source;
        for (String methodPrefix : approvedTaskWriteLockMethods) {
            unapprovedSource = unapprovedSource.replace(sourceMethod(source, methodPrefix), "");
        }

        List<String> violations = new ArrayList<>();
        if (unapprovedSource.contains("withTaskLock(")
                || unapprovedSource.contains("withTaskWriteLock(")) {
            violations.add(taskManagerPath + " uses task write lock outside lifecycle/intake/progress/audit methods");
        }

        String claimReady = sourceMethod(source, "public List<ClaimedTaskWork> claimReady");
        for (String forbidden : List.of("withTaskLock(", "withTaskWorkReadLock(", "withTaskWriteLock(")) {
            if (claimReady.contains(forbidden)) {
                violations.add(taskManagerPath + " claimReady must stay runtime-owned and not take task locks: "
                        + forbidden);
            }
        }

        assertTrue(violations.isEmpty(),
                "Runtime claim/result paths must not be serialized by task write locks. "
                        + "Task write locks are reserved for lifecycle, intake, progress, and audit boundaries:\n"
                        + String.join("\n", violations));
    }

    @Test
    void schedulingKernelDoesNotReadWorkerLevelCapabilityAsDecisionTruth() throws IOException {
        Map<Path, List<Pattern>> guardedFiles = Map.ofEntries(
                Map.entry(MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/model/WorkerSchedulingView.java"),
                        List.of(Pattern.compile("\\.getSupportedProjects\\s*\\("),
                                Pattern.compile("\\.getSupportedEventCodes\\s*\\("))),
                Map.entry(MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/strategy/RuleBasedTaskWorkerMatchingStrategy.java"),
                        List.of(Pattern.compile("\\.getSupportedProjects\\s*\\("),
                                Pattern.compile("\\.getSupportedEventCodes\\s*\\("))),
                Map.entry(MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/listener/SimpleTaskDispatchBinder.java"),
                        List.of(Pattern.compile("\\.getSupportedEventCodes\\s*\\(")))
        );

        List<String> violations = new ArrayList<>();
        for (Map.Entry<Path, List<Pattern>> guardedFile : guardedFiles.entrySet()) {
            String source = Files.readString(guardedFile.getKey(), StandardCharsets.UTF_8);
            for (Pattern pattern : guardedFile.getValue()) {
                if (pattern.matcher(source).find()) {
                    violations.add(guardedFile.getKey()
                            + " reads Worker.supportedProjects/supportedEventCodes in scheduling decision path");
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "WG-4 capability truth must be materialized from WorkerGroup snapshot/index truth. "
                        + "Worker-level supportedProjects/supportedEventCodes may remain migration inputs "
                        + "or legacy diagnostics, but not scheduling decision truth:\n"
                        + String.join("\n", violations));
    }

    @Test
    void eventMetadataFirstWaveDoesNotIntroduceUnifiedRuntimeOwner() throws IOException {
        Pattern unifiedRuntimeOwner = Pattern.compile(
                "\\b(?:class|interface|record)\\s+(?:UnifiedEventService|UnifiedEventEnvelope)\\b");

        List<String> violations = new ArrayList<>();
        for (Path root : repositoryProductionSourceRoots()) {
            for (Path path : javaSourceFiles(root)) {
                String source = Files.readString(path, StandardCharsets.UTF_8);
                if (unifiedRuntimeOwner.matcher(source).find()) {
                    violations.add(path + " declares a unified event runtime owner");
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "UE-0 through UE-3 are event-metadata and owner-boundary work only. "
                        + "Do not introduce UnifiedEventService or a runtime UnifiedEventEnvelope carrier "
                        + "in the first wave:\n"
                        + String.join("\n", violations));
    }

    @Test
    void eventMetadataDoesNotDriveSchedulingResultOrWorkerStateOwnersDirectly() throws IOException {
        Path repo = repositoryRoot();
        Map<String, GuardedSourceArea> guardedAreas = Map.ofEntries(
                Map.entry("PriorityClass -> scheduling/resource/runtime queue",
                        new GuardedSourceArea(
                                List.of(
                                        repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/assignment"),
                                        repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/load"),
                                        repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/listener"),
                                        repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/policy"),
                                        repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/resource"),
                                        repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/runtime"),
                                        repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/strategy"),
                                        repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/worker"),
                                        repo.resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery")
                                ),
                                Pattern.compile("\\bPriorityClass\\b|\\.getPriorityClass\\s*\\("))),
                Map.entry("response/convergence metadata -> result finality",
                        new GuardedSourceArea(
                                List.of(
                                        repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/TaskManager.java"),
                                        repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/TaskResultService.java"),
                                        repo.resolve("platform_infra/mass-runtime-api/src/main/java/com/xa/mass/runtime/api/TaskResultRuntime.java"),
                                        repo.resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/RuntimeTaskResultIngestChannel.java"),
                                        repo.resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/RedisTaskResultIngestChannel.java")
                                ),
                                Pattern.compile("\\b(?:ResponseMode|DeliveryAcknowledgementMode|EventConvergenceMode)\\b|\\.get(?:ResponseMode|DeliveryAcknowledgementMode|ConvergenceMode)\\s*\\("))),
                Map.entry("TargetScope -> new control-plane runtime path",
                        new GuardedSourceArea(
                                List.of(
                                        repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/worker"),
                                        repo.resolve("transport/transport_api/src/main/java/com/xa/mass/transport/channel/WorkerSystemEventChannel.java"),
                                        repo.resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/RuntimeEventBusWorkerSystemEventChannel.java")
                                ),
                                Pattern.compile("\\bTargetScope\\b|\\.getTargetScope\\s*\\("))),
                Map.entry("worker command/state report -> task result owner",
                        new GuardedSourceArea(
                                List.of(
                                        repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/TaskResultService.java"),
                                        repo.resolve("platform_infra/mass-runtime-api/src/main/java/com/xa/mass/runtime/api/TaskResultRuntime.java"),
                                        repo.resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/RuntimeTaskResultIngestChannel.java"),
                                        repo.resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/RedisTaskResultIngestChannel.java")
                                ),
                                Pattern.compile("\\b(?:WorkerCommand(?:Ack|Status)?|WorkerStateReport)\\b"))),
                Map.entry("worker state report -> reachability truth",
                        new GuardedSourceArea(
                                List.of(
                                        repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/worker/WorkerReachabilityView.java"),
                                        repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/worker/WorkerManager.java")
                                ),
                                Pattern.compile("\\bWorkerStateReport\\b")))
        );

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, GuardedSourceArea> guardedArea : guardedAreas.entrySet()) {
            for (Path path : guardedArea.getValue().sourceFiles()) {
                String source = Files.readString(path, StandardCharsets.UTF_8);
                if (guardedArea.getValue().forbiddenPattern().matcher(source).find()) {
                    violations.add(path + " leaks event metadata into owner path: " + guardedArea.getKey());
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Event metadata is descriptive and policy-input data in UE-0 through UE-3. "
                        + "It must not directly drive queue ordering, result finality, worker control paths, "
                        + "or reachability truth:\n"
                        + String.join("\n", violations));
    }

    @Test
    void eventDescriptorMetadataImportsStayOutOfKernelRuntimeTransportAndTraceOwners() throws IOException {
        Path repo = repositoryRoot();
        Pattern descriptorMetadataImport = Pattern.compile(
                "\\bimport\\s+com\\.xa\\.mass\\.base\\.event\\.[A-Za-z0-9_]+\\s*;");
        List<Path> guardedRoots = List.of(
                repo.resolve("xa-mass-engine/src/main/java"),
                repo.resolve("platform_infra/mass-runtime-api/src/main/java"),
                repo.resolve("transport/transport_api/src/main/java"),
                repo.resolve("transport/transport_runtime/src/main/java"),
                repo.resolve("platform_infra/mass-trace-sink/src/main/java"),
                repo.resolve("xa-mass-trace/src/main/java")
        );

        List<String> violations = new ArrayList<>();
        Path kernelEventRoutePackage = repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/event").normalize();
        for (Path root : guardedRoots) {
            for (Path path : javaSourceFiles(root)) {
                if (path.normalize().startsWith(kernelEventRoutePackage)) {
                    continue;
                }
                String source = Files.readString(path, StandardCharsets.UTF_8);
                if (descriptorMetadataImport.matcher(source).find()) {
                    violations.add(path + " imports descriptor metadata into a kernel/runtime/transport/trace owner");
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Event descriptor metadata belongs to descriptor/catalog/read surfaces in the first wave. "
                        + "Kernel, runtime, transport, and trace owner paths must not import it directly:\n"
                        + String.join("\n", violations));
    }

    @Test
    void kernelEventRegistrationPackageStaysRouteOnly() throws IOException {
        Path repo = repositoryRoot();
        Path eventPackage = repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/event");
        Pattern lifecycleOwnerDependency = Pattern.compile(String.join("|", List.of(
                "\\bTaskManager\\b",
                "\\bTaskResultService\\b",
                "\\bTaskResultRuntime\\b",
                "\\bTaskWorkRuntime\\b",
                "\\bWorkerManager\\b",
                "\\bWorkerReachabilityView\\b",
                "\\bWorkerLoadView\\b",
                "\\bWorkerSystemEventChannel\\b",
                "\\bWorkerCommand(?:Ack|Status)?\\b",
                "\\bWorkerStateReport\\b",
                "\\bWorkerCapabilityReport\\b",
                "\\bimport\\s+com\\.xa\\.mass\\.transport\\.",
                "\\bimport\\s+com\\.xa\\.mass\\.runtime\\."
        )));

        List<String> violations = new ArrayList<>();
        for (Path path : javaSourceFiles(eventPackage)) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            if (lifecycleOwnerDependency.matcher(source).find()) {
                violations.add(path + " turns kernel event routing into a lifecycle owner");
            }
        }

        assertTrue(violations.isEmpty(),
                "The EWC-2 kernel event package is a route-only handler registration boundary. "
                        + "It must not own task result, worker command/state/capability, presence, "
                        + "or runtime lifecycle mutation:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerSystemEventChannelStaysTransportIngressNotLifecycleOwner() throws IOException {
        Path repo = repositoryRoot();
        Map<String, GuardedSourceArea> guardedAreas = Map.ofEntries(
                Map.entry("system event channel -> engine dependency",
                        new GuardedSourceArea(
                                List.of(
                                        repo.resolve("transport/transport_api/src/main/java/com/xa/mass/transport/channel/WorkerSystemEventChannel.java"),
                                        repo.resolve("transport/transport_api/src/main/java/com/xa/mass/transport/channel/NoopWorkerSystemEventChannel.java"),
                                        repo.resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/RuntimeEventBusWorkerSystemEventChannel.java"),
                                        repo.resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/TracingWorkerSystemEventChannel.java")
                                ),
                                Pattern.compile("\\bimport\\s+com\\.xa\\.mass\\.engine\\."))),
                Map.entry("system event channel -> task result payload",
                        new GuardedSourceArea(
                                List.of(
                                        repo.resolve("transport/transport_api/src/main/java/com/xa/mass/transport/channel/WorkerSystemEventChannel.java"),
                                        repo.resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/RuntimeEventBusWorkerSystemEventChannel.java"),
                                        repo.resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/TracingWorkerSystemEventChannel.java")
                                ),
                                Pattern.compile("\\bTaskResultReport\\b|\\bTaskResultRuntime\\b"))),
                Map.entry("system event channel -> command/state owner",
                        new GuardedSourceArea(
                                List.of(
                                        repo.resolve("transport/transport_api/src/main/java/com/xa/mass/transport/channel/WorkerSystemEventChannel.java"),
                                        repo.resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/RuntimeEventBusWorkerSystemEventChannel.java"),
                                        repo.resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/TracingWorkerSystemEventChannel.java")
                                ),
                                Pattern.compile("\\bWorkerCommand(?:Ack|Status)?\\b|\\bWorkerStateReport\\b")))
        );

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, GuardedSourceArea> guardedArea : guardedAreas.entrySet()) {
            for (Path path : guardedArea.getValue().sourceFiles()) {
                String source = Files.readString(path, StandardCharsets.UTF_8);
                if (guardedArea.getValue().forbiddenPattern().matcher(source).find()) {
                    violations.add(path + " leaks worker system event channel into owner path: "
                            + guardedArea.getKey());
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "WorkerSystemEventChannel is a transport ingress seam for presence signals. "
                        + "It must not become a lifecycle owner or import engine scheduling/result paths:\n"
                        + String.join("\n", violations));
    }

    @Test
    void futureWorkerCommandAndStateReportDoNotPolluteTaskResultReachabilityLoadOrSchedulingOwners() throws IOException {
        Path repo = repositoryRoot();
        Pattern workerControlOrState = Pattern.compile(
                "\\bWorkerCommand(?:Ack)?\\b|\\bWorkerStateReport\\b");
        Map<String, GuardedSourceArea> guardedAreas = Map.ofEntries(
                Map.entry("task result owner",
                        new GuardedSourceArea(
                                List.of(
                                        repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/TaskResultService.java"),
                                        repo.resolve("platform_infra/mass-runtime-api/src/main/java/com/xa/mass/runtime/api/TaskResultRuntime.java"),
                                        repo.resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/RuntimeTaskResultIngestChannel.java"),
                                        repo.resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/RedisTaskResultIngestChannel.java")
                                ),
                                workerControlOrState)),
                Map.entry("reachability read model",
                        new GuardedSourceArea(
                                List.of(
                                        repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/worker/WorkerReachabilityView.java")
                                ),
                                workerControlOrState)),
                Map.entry("load read model",
                        new GuardedSourceArea(
                                List.of(
                                        repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/load")
                                ),
                                workerControlOrState)),
                Map.entry("scheduling hot path",
                        new GuardedSourceArea(
                                List.of(
                                        repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/assignment"),
                                        repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/listener"),
                                        repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/policy"),
                                        repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/resource"),
                                        repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/strategy")
                                ),
                                workerControlOrState))
        );

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, GuardedSourceArea> guardedArea : guardedAreas.entrySet()) {
            for (Path path : guardedArea.getValue().sourceFiles()) {
                String source = Files.readString(path, StandardCharsets.UTF_8);
                if (guardedArea.getValue().forbiddenPattern().matcher(source).find()) {
                    violations.add(path + " consumes future worker command/state shape in "
                            + guardedArea.getKey());
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Worker command/status and worker state reports need dedicated future owners. "
                        + "They must not enter task result, reachability, load, or scheduling owners directly:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerCommandOwnerDoesNotDependOnTaskResultTaskWorkOrTransportDelivery() throws IOException {
        Path repo = repositoryRoot();
        Path commandPackage = repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/command");
        Pattern forbiddenDependency = Pattern.compile(String.join("|", List.of(
                "\\bTaskResult(?:Service|Runtime|Report)\\b",
                "\\bTaskWorkRuntime\\b",
                "\\bTaskAssignWorker\\b",
                "\\bTaskDispatchBinder\\b",
                "\\bWorkerSystemEventChannel\\b",
                "\\bWorkerReachabilityView\\b",
                "\\bWorkerLoadView\\b",
                "\\bimport\\s+com\\.xa\\.mass\\.transport\\.",
                "\\bimport\\s+com\\.xa\\.mass\\.runtime\\."
        )));

        List<String> violations = new ArrayList<>();
        for (Path path : javaSourceFiles(commandPackage)) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            if (forbiddenDependency.matcher(source).find()) {
                violations.add(path + " couples worker command owner to task result, task work, or transport delivery");
            }
        }

        assertTrue(violations.isEmpty(),
                "Worker command lifecycle owns request/status truth, command-specific delivery handoff, "
                        + "and owner-decided acknowledgement/status ingest. It must not use task-result "
                        + "convergence, task-work dispatch, transport delivery, reachability, or load owners:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerStateProjectionOwnerDoesNotDependOnReachabilityLoadSchedulingResultOrTransport() throws IOException {
        Path repo = repositoryRoot();
        Path workerPackage = repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/worker");
        Pattern stateProjectionSource = Pattern.compile("\\bWorkerState(?:Report|Projection)\\b");
        Pattern forbiddenDependency = Pattern.compile(String.join("|", List.of(
                "\\bTaskResult(?:Service|Runtime|Report)\\b",
                "\\bTaskWorkRuntime\\b",
                "\\bTaskAssignWorker\\b",
                "\\bTaskDispatchBinder\\b",
                "\\bRuleBasedTaskWorkerMatchingStrategy\\b",
                "\\bWorkerReachabilityView\\b",
                "\\bWorkerLoadView\\b",
                "\\bimport\\s+com\\.xa\\.mass\\.engine\\.strategy\\.",
                "\\bimport\\s+com\\.xa\\.mass\\.engine\\.load\\.",
                "\\bimport\\s+com\\.xa\\.mass\\.transport\\.",
                "\\bimport\\s+com\\.xa\\.mass\\.runtime\\."
        )));

        List<String> violations = new ArrayList<>();
        for (Path path : javaSourceFiles(workerPackage)) {
            if (!path.getFileName().toString().startsWith("WorkerState")) {
                continue;
            }
            String source = Files.readString(path, StandardCharsets.UTF_8);
            if (stateProjectionSource.matcher(source).find() && forbiddenDependency.matcher(source).find()) {
                violations.add(path + " couples worker state projection to reachability/load/scheduling/result/transport");
            }
        }

        assertTrue(violations.isEmpty(),
                "Worker state projection owns bounded diagnostic state only. It must not write or depend on "
                        + "reachability, load, scheduling, task-result, runtime, or transport owners:\n"
                        + String.join("\n", violations));
    }

    @Test
    void taskStageEvidenceOwnerDoesNotDependOnResultRuntimeTaskWorkRuntimeOrScheduling() throws IOException {
        Path repo = repositoryRoot();
        Path stagePackage = repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/stage");
        Pattern forbiddenDependency = Pattern.compile(String.join("|", List.of(
                "\\bTaskResult(?:Service|Runtime|Report|RuntimeRow|CallbackDraft|FinalDraft)\\b",
                "\\bTaskWorkRuntime\\b",
                "\\bTaskWorkResult\\b",
                "\\bTaskManager\\b",
                "\\bTaskAssignWorker\\b",
                "\\bTaskDispatchBinder\\b",
                "\\bRuleBasedTaskWorkerMatchingStrategy\\b",
                "\\bWorkerLoadView\\b",
                "\\bWorkerReachabilityView\\b",
                "\\bimport\\s+com\\.xa\\.mass\\.runtime\\.",
                "\\bimport\\s+com\\.xa\\.mass\\.transport\\.",
                "\\bimport\\s+com\\.xa\\.mass\\.engine\\.strategy\\.",
                "\\bimport\\s+com\\.xa\\.mass\\.engine\\.load\\."
        )));

        List<String> violations = new ArrayList<>();
        for (Path path : javaSourceFiles(stagePackage)) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            if (forbiddenDependency.matcher(source).find()) {
                violations.add(path + " couples stage evidence to final result/runtime/scheduling owner");
            }
        }

        assertTrue(violations.isEmpty(),
                "Task stage evidence owns bounded intermediate evidence only. It must not commit stable-final "
                        + "results, mutate task-work runtime, or depend on scheduling/transport owners:\n"
                        + String.join("\n", violations));
    }

    @Test
    void taskStageEvidenceDoesNotEnterPublicResultRuntimeRows() throws IOException {
        Path repo = repositoryRoot();
        Pattern stageEvidence = Pattern.compile("\\bTaskStageEvidence\\b|\\bTASK_STAGE_EVIDENCE_APPLIED\\b");
        List<Path> publicResultOwners = List.of(
                repo.resolve("platform_infra/mass-runtime-api/src/main/java/com/xa/mass/runtime/api/TaskResultRuntime.java"),
                repo.resolve("platform_infra/mass-runtime-api/src/main/java/com/xa/mass/runtime/api/TaskResultRuntimeRow.java"),
                repo.resolve("platform_infra/mass-runtime-memory/src/main/java/com/xa/mass/runtime/memory/InMemoryTaskResultRuntime.java"),
                repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/TaskResultService.java"),
                repo.resolve("xa-mass-sdk/src/main/java/com/xa/mass/sdk/MassSdkApplication.java"),
                repo.resolve("xa-mass-server/src/main/java/com/xa/mass/api/internal/TaskApiController.java")
        );

        List<String> violations = new ArrayList<>();
        for (Path path : publicResultOwners) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            if (stageEvidence.matcher(source).find()) {
                violations.add(path + " exposes stage evidence through stable-final result rows or public result reads");
            }
        }

        assertTrue(violations.isEmpty(),
                "EWC-6 stage evidence must stay separate from public stable-final result rows. "
                        + "/results and TaskResultRuntimeRow must remain final-result surfaces:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerCapabilitySelfReportDoesNotBypassWorkerRegistrySnapshotOwner() throws IOException {
        Path repo = repositoryRoot();
        Pattern capabilityReport = Pattern.compile("\\bWorkerCapabilityReport\\b|\\bCapabilitySelfReport\\b");
        Map<String, GuardedSourceArea> guardedAreas = Map.ofEntries(
                Map.entry("system event channel",
                        new GuardedSourceArea(
                                List.of(
                                        repo.resolve("transport/transport_api/src/main/java/com/xa/mass/transport/channel/WorkerSystemEventChannel.java"),
                                        repo.resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/RuntimeEventBusWorkerSystemEventChannel.java"),
                                        repo.resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/TracingWorkerSystemEventChannel.java")
                                ),
                                capabilityReport)),
                Map.entry("matching/acquisition path",
                        new GuardedSourceArea(
                                List.of(
                                        repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/strategy"),
                                        repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/model/WorkerSchedulingView.java")
                                ),
                                capabilityReport)),
                Map.entry("runtime result owner",
                        new GuardedSourceArea(
                                List.of(
                                        repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/TaskResultService.java"),
                                        repo.resolve("platform_infra/mass-runtime-api/src/main/java/com/xa/mass/runtime/api/TaskResultRuntime.java")
                                ),
                                capabilityReport))
        );

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, GuardedSourceArea> guardedArea : guardedAreas.entrySet()) {
            for (Path path : guardedArea.getValue().sourceFiles()) {
                String source = Files.readString(path, StandardCharsets.UTF_8);
                if (guardedArea.getValue().forbiddenPattern().matcher(source).find()) {
                    violations.add(path + " consumes capability self-report outside worker registry owner: "
                            + guardedArea.getKey());
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Future worker capability self-report must flow through a worker capability/report owner "
                        + "that refreshes WorkerManager / WorkerRegistrySnapshot / WorkerCandidateIndex truth. "
                        + "It must not bypass that owner through system-event, matching, or result paths:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerCapabilityReportHandlerStaysInWorkerOwnerBoundary() throws IOException {
        Path handlerPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/worker/WorkerCapabilityReportEventHandler.java");
        String source = Files.readString(handlerPath, StandardCharsets.UTF_8);

        Map<String, Pattern> forbiddenPatterns = Map.ofEntries(
                Map.entry("transport system event channel", Pattern.compile("\\bWorkerSystemEventChannel\\b")),
                Map.entry("task result owner", Pattern.compile("\\bTaskResult(?:Service|Runtime|Report)\\b")),
                Map.entry("task work runtime", Pattern.compile("\\bTaskWorkRuntime\\b")),
                Map.entry("matching/ranking owner", Pattern.compile("\\bRuleBasedTaskWorkerMatchingStrategy\\b|\\bWorkerCandidateRanker\\b")),
                Map.entry("reachability owner", Pattern.compile("\\bWorkerReachabilityView\\b")),
                Map.entry("load owner", Pattern.compile("\\bWorkerLoadView\\b"))
        );

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Pattern> forbiddenPattern : forbiddenPatterns.entrySet()) {
            if (forbiddenPattern.getValue().matcher(source).find()) {
                violations.add(handlerPath + " reaches outside capability owner boundary: "
                        + forbiddenPattern.getKey());
            }
        }

        assertTrue(violations.isEmpty(),
                "Worker capability report event handling may parse an event payload and delegate "
                        + "to WorkerManager / WorkerCapabilityAuthority only. It must not become "
                        + "transport presence, task result, matching, reachability, or load ownership:\n"
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

    private static Path repositoryRoot() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (Files.exists(cwd.resolve("xa-mass-engine/pom.xml"))) {
            return cwd;
        }
        Path parent = cwd.getParent();
        if (parent != null && Files.exists(parent.resolve("xa-mass-engine/pom.xml"))) {
            return parent;
        }
        return cwd;
    }

    private static List<Path> repositoryProductionSourceRoots() {
        Path repo = repositoryRoot();
        return List.of(
                repo.resolve("xa-mass-sdk-api/src/main/java"),
                repo.resolve("xa-mass-base/src/main/java"),
                repo.resolve("xa-mass-engine/src/main/java"),
                repo.resolve("xa-mass-sdk/src/main/java"),
                repo.resolve("xa-mass-server/src/main/java"),
                repo.resolve("transport/transport_api/src/main/java"),
                repo.resolve("transport/transport_runtime/src/main/java"),
                repo.resolve("transport/polling-adapter/src/main/java"),
                repo.resolve("transport/socket-adapter/src/main/java"),
                repo.resolve("transport/websocket-adapter/src/main/java"),
                repo.resolve("platform_infra/mass-runtime-api/src/main/java"),
                repo.resolve("platform_infra/mass-trace-sink/src/main/java")
        );
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

    private static String sourceMethod(String source, String methodPrefix) {
        int start = source.indexOf(methodPrefix);
        assertTrue(start >= 0, "method not found: " + methodPrefix);
        int brace = source.indexOf('{', start);
        assertTrue(brace >= 0, "method body not found: " + methodPrefix);
        int depth = 0;
        for (int i = brace; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        throw new AssertionError("method body did not close: " + methodPrefix);
    }

    private record GuardedSourceArea(List<Path> roots, Pattern forbiddenPattern) {
        private List<Path> sourceFiles() throws IOException {
            List<Path> files = new ArrayList<>();
            for (Path root : roots) {
                if (Files.isRegularFile(root)) {
                    files.add(root);
                } else {
                    files.addAll(javaSourceFiles(root));
                }
            }
            return files;
        }
    }

}
