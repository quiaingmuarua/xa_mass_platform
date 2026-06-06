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
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineSchedulingCoreArchitectureGuardTest {

    private static final Path MAIN_SOURCE_ROOT = Path.of("src/main/java");
    private static final Path TEST_SOURCE_ROOT = Path.of("src/test/java");
    private static final String RETIRED_PRESET_HELPER = "TaskPolicyPreset" + "Semantics";
    private static final Path WORKER_MANAGER_SOURCE = Path.of("..", "xa-mass-worker-runtime", "src", "main", "java",
            "com", "xa", "mass", "worker", "runtime", "WorkerManager.java");

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
    void ruleBasedMatchingStrategyUsesNarrowRuleContractsOnly() throws IOException {
        Path strategyPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/strategy/RuleBasedTaskWorkerMatchingStrategy.java");
        String source = Files.readString(strategyPath, StandardCharsets.UTF_8);

        Map<String, Pattern> forbiddenPatterns = Map.ofEntries(
                Map.entry("RuleManager", Pattern.compile("\\bRuleManager\\b")),
                Map.entry("RuleStorage", Pattern.compile("\\bRuleStorage\\b")),
                Map.entry("rule CRUD method", Pattern.compile(
                        "\\b(?:addRule|addRules|deleteRule|updateRule|clear|getAllRules|getRulesByType)\\s*\\(")),
                Map.entry("broad evaluator method", Pattern.compile("\\bevaluateRules\\s*\\("))
        );

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Pattern> forbiddenPattern : forbiddenPatterns.entrySet()) {
            if (forbiddenPattern.getValue().matcher(source).find()) {
                violations.add(strategyPath + " uses forbidden broad rule dependency: "
                        + forbiddenPattern.getKey());
            }
        }
        assertTrue(source.contains("MatchingRuleSetProvider"),
                "RuleBasedTaskWorkerMatchingStrategy must depend on MatchingRuleSetProvider");
        assertTrue(source.contains("MatchingRuleEvaluator"),
                "RuleBasedTaskWorkerMatchingStrategy must depend on MatchingRuleEvaluator");
        assertTrue(violations.isEmpty(),
                "Matching must consume narrow rule contracts, not a CRUD-shaped rule manager or storage facade:\n"
                        + String.join("\n", violations));
    }

    @Test
    void engineMainSourcesDoNotImportRuntimeMemoryOrStorageImplementations() throws IOException {
        Pattern forbiddenImport = Pattern.compile(String.join("|", List.of(
                "\\bimport\\s+com\\.xa\\.mass\\.runtime\\.memory\\.",
                "\\bimport\\s+com\\.xa\\.mass\\.storage\\.memory\\.",
                "\\bimport\\s+com\\.xa\\.mass\\.storage\\.jdbc\\."
        )));

        List<String> violations = new ArrayList<>();
        for (Path path : javaSourceFiles(MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine"))) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            if (forbiddenImport.matcher(source).find()) {
                violations.add(path + " imports a runtime/storage implementation package");
            }
        }

        assertTrue(violations.isEmpty(),
                "Engine production code must consume runtime and storage contracts only. "
                        + "Implementation defaults belong in SDK/server/test assembly:\n"
                        + String.join("\n", violations));
    }

    @Test
    void enginePomKeepsRuntimeMemoryOutOfProductionScope() throws IOException {
        Path enginePom = repositoryRoot().resolve("xa-mass-engine/pom.xml");
        String pom = Files.readString(enginePom, StandardCharsets.UTF_8);
        Pattern runtimeMemoryDependency = Pattern.compile(
                "<dependency>\\s*<groupId>com\\.xa\\.mass</groupId>\\s*"
                        + "<artifactId>mass-runtime-memory</artifactId>.*?</dependency>",
                Pattern.DOTALL
        );

        java.util.regex.Matcher matcher = runtimeMemoryDependency.matcher(pom);
        List<String> violations = new ArrayList<>();
        while (matcher.find()) {
            String dependencyBlock = matcher.group();
            if (!Pattern.compile("<scope>\\s*test\\s*</scope>").matcher(dependencyBlock).find()) {
                violations.add("xa-mass-engine declares mass-runtime-memory outside test scope");
            }
        }

        assertTrue(violations.isEmpty(),
                "mass-runtime-memory is an implementation fixture for engine tests, not an engine production dependency:\n"
                        + String.join("\n", violations));
    }

    @Test
    void ruleApiRoutesDoNotUseRuntimeRulesNamespace() throws IOException {
        Path repo = repositoryRoot();
        Pattern runtimeRuleRoute = Pattern.compile("/api/v1/runtime/rules|/status/rules");
        List<Path> guardedRoots = List.of(
                repo.resolve("xa-mass-server/src/main/java"),
                repo.resolve("xa-mass-server/src/test/java"),
                repo.resolve("doc/INTERNAL_API_REFERENCE.md"),
                repo.resolve("doc/E2E_BASELINE.md"),
                repo.resolve("xa-mass-server/README.md")
        );

        List<String> violations = new ArrayList<>();
        for (Path root : guardedRoots) {
            for (Path path : sourceOrSingleFile(root)) {
                String source = Files.readString(path, StandardCharsets.UTF_8);
                if (runtimeRuleRoute.matcher(source).find()) {
                    violations.add(path + " still references the retired runtime rule route");
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Rule definition APIs are admin/control-plane surfaces, not runtime APIs. "
                        + "Do not reintroduce `/api/v1/runtime/rules` or `/status/rules` aliases:\n"
                        + String.join("\n", violations));
    }

    @Test
    void storageModulesDoNotImportConcreteRuleEvaluatorImplementations() throws IOException {
        Path repo = repositoryRoot();
        Pattern forbiddenEvaluatorOwner = Pattern.compile(String.join("|", List.of(
                "\\bimport\\s+com\\.xa\\.mass\\.engine\\.rules\\.",
                "\\bQLExpressRuleEvaluator\\b",
                "\\bRegistryBackedMatchingRuleEvaluator\\b",
                "\\bRuleEvaluatorRegistries\\b",
                "\\bimport\\s+com\\.alibaba\\.qlexpress4\\."
        )));

        List<Path> guardedRoots = List.of(
                repo.resolve("platform_infra/mass-storage-api/src/main/java"),
                repo.resolve("platform_infra/mass-storage-memory/src/main/java"),
                repo.resolve("platform_infra/mass-storage-jdbc/src/main/java")
        );

        List<String> violations = new ArrayList<>();
        for (Path root : guardedRoots) {
            for (Path path : javaSourceFiles(root)) {
                String source = Files.readString(path, StandardCharsets.UTF_8);
                if (forbiddenEvaluatorOwner.matcher(source).find()) {
                    violations.add(path + " imports concrete evaluator runtime code");
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Storage modules own rule definitions only. Concrete evaluator registration and QLExpress "
                        + "runtime code belong to engine rule assembly:\n"
                        + String.join("\n", violations));
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
                Map.entry("workerContextOccupied", Pattern.compile("\\bworkerContextOccupied\\b")),
                Map.entry("worker-row supported project fallback",
                        Pattern.compile("\\bcandidateRow\\.supportedProjects\\s*\\(")),
                Map.entry("worker-row supported event fallback",
                        Pattern.compile("\\bcandidateRow\\.supportedEventCodes\\s*\\("))
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
                        + "read account-slot identity, lifecycle state, or worker-row capability fallback:\n"
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
    void ruleBasedMatchingEvaluatesDeclarativeRuleContextOnly() throws IOException {
        Path strategyPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/strategy/RuleBasedTaskWorkerMatchingStrategy.java");
        String source = Files.readString(strategyPath, StandardCharsets.UTF_8);

        Pattern fullContextEvaluation = Pattern.compile(
                "ruleEvaluator\\.evaluate\\s*\\([^;]*getContext\\s*\\(",
                Pattern.DOTALL);
        Pattern declarativeContextEvaluation = Pattern.compile(
                "ruleEvaluator\\.evaluate\\s*\\([^;]*getRuleContext\\s*\\(",
                Pattern.DOTALL);

        assertFalse(fullContextEvaluation.matcher(source).find(),
                "Rule evaluation must not consume the full diagnostic WorkerMatchContext snapshot. "
                        + "Runtime evidence belongs to prefilter, rank, reserve, and diagnostics.");
        assertTrue(declarativeContextEvaluation.matcher(source).find(),
                "RuleBasedTaskWorkerMatchingStrategy must evaluate the declarative rule context.");
    }

    @Test
    void workloadClassBudgetConsumerUsesResolvedTaskSchedulingPolicy() throws IOException {
        Path budgetPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/assignment/DefaultWorkerBudgetPolicy.java");
        Path allocationPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/assignment/DefaultAssignmentAllocationPolicy.java");
        String budgetSource = Files.readString(budgetPath, StandardCharsets.UTF_8);
        String allocationSource = Files.readString(allocationPath, StandardCharsets.UTF_8);

        assertFalse(budgetSource.contains("getExecutionSpec().getWorkloadClass()"),
                "DefaultWorkerBudgetPolicy must consume ResolvedTaskSchedulingPolicy.workloadClass(), "
                        + "not rediscover workloadClass from raw Task.");
        assertTrue(budgetSource.contains("ResolvedTaskSchedulingPolicy"),
                "DefaultWorkerBudgetPolicy must make the resolved task scheduling view its input.");
        assertTrue(allocationSource.contains("workerBudgetPolicy.resolve(\n                taskPolicy,"),
                "DefaultAssignmentAllocationPolicy must pass the resolved task scheduling policy into "
                        + "worker budget resolution.");
    }

    @Test
    void legacyTaskContractBehaviorReadsStayBehindPresetDefinition() throws IOException {
        Set<String> allowedRelativePaths = Set.of(
                "com/xa/mass/engine/runtime/scheduling/TaskPolicyPresetDefinition.java",
                "com/xa/mass/engine/runtime/scheduling/TaskPolicyPresetResolution.java",
                "com/xa/mass/engine/TaskManager.java"
        );
        Pattern directContractBehaviorRead = Pattern.compile("\\bTaskContract\\s*\\.|\\.getContract\\s*\\(");

        List<String> violations = new ArrayList<>();
        for (Path path : javaSourceFiles(MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine"))) {
            String relativePath = MAIN_SOURCE_ROOT.relativize(path).toString().replace('\\', '/');
            if (allowedRelativePaths.contains(relativePath)) {
                continue;
            }
            String source = Files.readString(path, StandardCharsets.UTF_8);
            if (directContractBehaviorRead.matcher(source).find()) {
                violations.add(relativePath);
            }
        }

        assertTrue(violations.isEmpty(),
                "TaskContract behavior interpretation must stay behind preset resolution "
                        + "during TPC convergence. Public/read/default shell handling is allowed in "
                        + "TaskManager only until later phases shrink the allowlist:\n"
                        + String.join("\n", violations));
    }

    @Test
    void terminalPolicyConsumesIdleClosePolicyNotLegacyPresetHelpers() throws IOException {
        List<Path> terminalPolicySources = List.of(
                MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/policy/ContractAwareTaskTerminalPolicy.java"),
                MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/policy/AllWorkFinalTaskTerminalPolicy.java")
        );

        List<String> violations = new ArrayList<>();
        for (Path sourcePath : terminalPolicySources) {
            String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
            if (source.contains(RETIRED_PRESET_HELPER)
                    || Pattern.compile("\\bTaskContract\\s*\\.|\\.getContract\\s*\\(").matcher(source).find()) {
                violations.add(sourcePath + " reads legacy preset helpers for terminal behavior");
            }
            if (!source.contains("IdleClosePolicy")) {
                violations.add(sourcePath + " does not consume explicit IdleClosePolicy");
            }
        }

        assertTrue(violations.isEmpty(),
                "Terminal policy must consume resolved IdleClosePolicy; TaskContract and legacy "
                        + "preset helpers are no longer terminal behavior truth:\n"
                        + String.join("\n", violations));
    }

    @Test
    void dispatchCadenceConsumersUseResolvedPolicyNotLegacyPresetHelpers() throws IOException {
        List<Path> dispatchSources = List.of(
                MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/TaskDispatchRequestService.java"),
                MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/watchdog/RuntimeReadyDispatchPump.java"),
                MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/EngineRuntimeKernel.java")
        );

        List<String> violations = new ArrayList<>();
        for (Path sourcePath : dispatchSources) {
            String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
            if (source.contains(RETIRED_PRESET_HELPER)
                    || Pattern.compile("\\bTaskContract\\s*\\.|\\.getContract\\s*\\(").matcher(source).find()) {
                violations.add(sourcePath + " reads legacy preset helpers for dispatch cadence");
            }
            if (!source.contains("DispatchCadence")) {
                violations.add(sourcePath + " does not consume resolved DispatchCadence");
            }
        }

        assertTrue(violations.isEmpty(),
                "Dispatch cadence owners must consume resolved DispatchCadence; TaskContract and legacy "
                        + "preset helpers are no longer dispatch behavior truth:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerResourcePolicyConsumesResolvedResourceModeNotLegacyForeground() throws IOException {
        Path policyPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/resource/DefaultWorkerDispatchResourcePolicy.java");
        String source = Files.readString(policyPath, StandardCharsets.UTF_8);

        List<String> violations = new ArrayList<>();
        if (source.contains(RETIRED_PRESET_HELPER)
                || Pattern.compile("\\.isForeground\\s*\\(|\\.getExecutionSpec\\s*\\(\\)\\.isForeground\\s*\\(")
                .matcher(source)
                .find()) {
            violations.add(policyPath + " reads legacy foreground preset helpers for resource mode");
        }
        if (!source.contains("WorkerResourceMode")) {
            violations.add(policyPath + " does not consume resolved WorkerResourceMode");
        }

        assertTrue(violations.isEmpty(),
                "Worker resource policy must consume resolved WorkerResourceMode; foreground and legacy "
                        + "preset helpers are no longer resource-mode truth:\n"
                        + String.join("\n", violations));
    }

    @Test
    void runtimeClaimRetryAndBackpressureConsumersUseResolvedPolicy() throws IOException {
        Path binderPath = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/listener/SimpleTaskDispatchBinder.java");
        Path assignWorkerPath = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/listener/TaskAssignWorker.java");
        Path resultServicePath = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/TaskResultService.java");
        Path taskManagerPath = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/TaskManager.java");
        Path lifecyclePath = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/TaskLifecycleService.java");
        String binderSource = Files.readString(binderPath, StandardCharsets.UTF_8);
        String assignWorkerSource = Files.readString(assignWorkerPath, StandardCharsets.UTF_8);
        String resultServiceSource = Files.readString(resultServicePath, StandardCharsets.UTF_8);
        String taskManagerSource = Files.readString(taskManagerPath, StandardCharsets.UTF_8);
        String lifecycleSource = Files.readString(lifecyclePath, StandardCharsets.UTF_8);

        List<String> violations = new ArrayList<>();
        if (!binderSource.contains("ResolvedTaskSchedulingPolicy taskPolicy")
                || !binderSource.contains("TASK_RUNTIME_CLAIM_OPTIONS_RESOLVER.resolve(\n                task,\n                taskPolicy,")) {
            violations.add(binderPath + " must pass resolved ClaimPolicy through ResolvedTaskSchedulingPolicy");
        }
        if (Pattern.compile("taskRuntimeRetryPolicyResolver\\.resolve\\s*\\(\\s*task\\s*,")
                .matcher(assignWorkerSource)
                .find()) {
            violations.add(assignWorkerPath + " resolves retry from raw Task instead of resolved task policy");
        }
        if (Pattern.compile("taskRuntimeRetryPolicyResolver\\.resolve\\s*\\(\\s*task\\s*,")
                .matcher(resultServiceSource)
                .find()) {
            violations.add(resultServicePath + " resolves retry from raw Task instead of resolved task policy");
        }
        if (resultServiceSource.contains(RETIRED_PRESET_HELPER)) {
            violations.add(resultServicePath + " reads legacy preset helpers for result finality");
        }
        if (Pattern.compile("enqueueOptionsResolver\\.resolve\\s*\\(\\s*task\\s*\\)")
                .matcher(taskManagerSource)
                .find()) {
            violations.add(taskManagerPath + " resolves enqueue backpressure from raw Task");
        }
        if (Pattern.compile("enqueueOptionsResolver\\(\\)\\.resolve\\s*\\(\\s*task\\s*\\)")
                .matcher(lifecycleSource)
                .find()) {
            violations.add(lifecyclePath + " resolves enqueue admission from raw Task");
        }

        assertTrue(violations.isEmpty(),
                "Claim, retry, result finality, and backpressure consumers must use resolved task "
                        + "policy fields instead of raw preset/profile semantics:\n"
                        + String.join("\n", violations));
    }

    @Test
    void rawTaskPolicyResolverEntriesAreNotPublicMainlineSurface() throws IOException {
        List<Path> resolverSources = List.of(
                MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/runtime/TaskRuntimeClaimOptionsResolver.java"),
                MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/runtime/TaskRuntimeRetryPolicyResolver.java"),
                MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/runtime/TaskRuntimeEnqueueOptionsResolver.java")
        );
        Map<String, Pattern> rawOverloadByFile = Map.of(
                "TaskRuntimeClaimOptionsResolver.java",
                Pattern.compile("public\\s+TaskWorkClaimOptions\\s+resolve\\s*\\(\\s*Task\\s+task\\s*,\\s*int"),
                "TaskRuntimeRetryPolicyResolver.java",
                Pattern.compile("public\\s+TaskRuntimeRetryPolicy\\s+resolve\\s*\\(\\s*Task\\s+task\\s*,\\s*long"),
                "TaskRuntimeEnqueueOptionsResolver.java",
                Pattern.compile("public\\s+WorkEnqueueOptions\\s+resolve\\s*\\(\\s*Task\\s+task\\s*\\)")
        );

        List<String> violations = new ArrayList<>();
        for (Path sourcePath : resolverSources) {
            String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
            Pattern rawOverload = rawOverloadByFile.get(sourcePath.getFileName().toString());
            if (rawOverload != null && rawOverload.matcher(source).find()) {
                violations.add(sourcePath + " exposes raw Task policy resolution as public mainline API");
            }
        }

        assertTrue(violations.isEmpty(),
                "Runtime claim/retry/enqueue policy resolvers must expose resolved-policy overloads "
                        + "as the production path. Raw Task overloads are package-private support only:\n"
                        + String.join("\n", violations));
    }

    @Test
    void engineRuntimeKernelWiresResolvedSchedulingPlaneThroughRuntimeOwners() throws IOException {
        Path kernelPath = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/EngineRuntimeKernel.java");
        String source = Files.readString(kernelPath, StandardCharsets.UTF_8);

        List<String> violations = new ArrayList<>();
        if (!source.contains("new DefaultWorkerDispatchResourcePolicy(schedulingPlaneResolver)")) {
            violations.add(kernelPath + " must build resource policy from the kernel schedulingPlaneResolver");
        }
        if (!source.contains("new WorkerDispatchResourceReleaser(\n"
                + "                    workerAdmissionRuntime,\n"
                + "                    resourcePolicy,\n"
                + "                    traceEventLogger\n"
                + "            )")) {
            violations.add(kernelPath + " must build a shared WorkerDispatchResourceReleaser");
        }
        if (!source.contains("resourcePolicy,\n"
                + "                    resourceReleaser,\n"
                + "                    schedulingPlaneResolver")) {
            violations.add(kernelPath + " must pass policy/releaser/resolver into SimpleTaskDispatchBinder");
        }
        if (!source.contains("traceEventLogger,\n"
                + "                            schedulingPlaneResolver,\n"
                + "                            new DefaultWorkerCandidateRanker(),\n"
                + "                            resourcePolicy,")) {
            violations.add(kernelPath + " must pass schedulingPlaneResolver/resourcePolicy into matching strategy");
        }
        if (!source.contains("new DefaultAssignmentAllocationPolicy(null, schedulingPlaneResolver)")) {
            violations.add(kernelPath + " must build assignment allocation policy from the kernel resolver");
        }
        if (!source.contains("resourceReleaser,\n"
                + "                    schedulingPlaneResolver")) {
            violations.add(kernelPath + " must pass resourceReleaser/resolver into TaskWorkerAssignListener");
        }
        if (!source.contains("config.getRuntimeReadyDispatchIdleBackoffPolicy(),\n"
                + "                    schedulingPlaneResolver")) {
            violations.add(kernelPath + " must pass schedulingPlaneResolver into RuntimeReadyDispatchPump");
        }

        assertTrue(violations.isEmpty(),
                "EngineRuntimeKernel owns production runtime assembly. Runtime owners must not each "
                        + "silently construct policy defaults or separate scheduling resolvers:\n"
                        + String.join("\n", violations));
    }

    @Test
    void computedSchedulingPlaneDoesNotIntroduceWritablePolicyTruth() throws IOException {
        List<String> forbiddenTypes = List.of(
                "ProjectSchedulingPolicy",
                "SchedulingPolicyCatalog",
                "ProjectSchedulingBinding",
                "TaskSchedulingPolicyDefinition",
                "WorkerSchedulingPolicyDefinition",
                "TaskSchedulingPolicyRepository",
                "WorkerSchedulingPolicyRepository",
                "SchedulingPolicyStore",
                "SchedulingPolicyDao"
        );

        List<String> violations = new ArrayList<>();
        for (Path path : javaSourceFiles(MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine"))) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            for (String forbiddenType : forbiddenTypes) {
                Pattern typeDeclaration = Pattern.compile(
                        "\\b(class|interface|record|enum)\\s+" + Pattern.quote(forbiddenType) + "\\b");
                if (typeDeclaration.matcher(source).find()) {
                    violations.add(path + " declares storage/catalog policy truth type: " + forbiddenType);
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Current Scheduling Plane path is computed defaults plus resolved views. "
                        + "Do not add root ProjectSchedulingPolicy, catalog/binding, or writable policy truth "
                        + "inside engine until a successor PSP decision names a concrete caller and owner:\n"
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
    void workerAccessTypesStayOutOfRootEnginePackage() throws IOException {
        Map<Path, String> retiredRootTypes = Map.ofEntries(
                Map.entry(MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/WorkerManager.java"), "WorkerManager"),
                Map.entry(MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/WorkerReachabilityState.java"),
                        "WorkerReachabilityState"),
                Map.entry(MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/WorkerReachabilityView.java"),
                        "WorkerReachabilityView"),
                Map.entry(MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/worker/WorkerReachabilityView.java"),
                        "WorkerReachabilityView")
        );
        Map<String, Pattern> retiredImports = Map.ofEntries(
                Map.entry("WorkerManager",
                        Pattern.compile("\\bimport\\s+com\\.xa\\.mass\\.engine\\.WorkerManager\\s*;")),
                Map.entry("WorkerReachabilityState",
                        Pattern.compile("\\bimport\\s+com\\.xa\\.mass\\.engine\\.WorkerReachabilityState\\s*;")),
                Map.entry("WorkerReachabilityView",
                        Pattern.compile("\\bimport\\s+com\\.xa\\.mass\\.engine\\.(?:worker\\.)?WorkerReachabilityView\\s*;"))
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
                "WorkerManager belongs in xa-mass-worker-runtime, while reachability contracts belong in "
                        + "xa-mass-worker-runtime evidence contracts. Do not keep engine-local reachability contract copies:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerGroupSnapshotDoesNotReadWorkerLevelCapabilityTruth() throws IOException {
        Path snapshotPath = repositoryRoot().resolve(
                "xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/WorkerRegistrySnapshot.java");
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
        Path workerReportOwnerPath = repositoryRoot().resolve(
                "xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/WorkerReportOwner.java");
        String source = Files.readString(workerReportOwnerPath, StandardCharsets.UTF_8);

        List<String> violations = new ArrayList<>();
        if (!Pattern.compile("\\bWorkerCapabilityAuthority\\b").matcher(source).find()) {
            violations.add(workerReportOwnerPath + " does not hold the WorkerCapabilityAuthority owner");
        }
        if (!Pattern.compile("\\bcapabilityAuthority\\.composeSnapshot\\s*\\(").matcher(source).find()) {
            violations.add(workerReportOwnerPath + " does not compose snapshots through WorkerCapabilityAuthority");
        }

        assertTrue(violations.isEmpty(),
                "WorkerReportOwner owns effective capability composition. WorkerManager may publish "
                        + "the active snapshot, but composition must flow through WorkerCapabilityAuthority:\n"
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
        Path repo = repositoryRoot();
        Path engineWorkerPackage = repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/worker");
        Path runtimeWorkerPackage = repo.resolve(
                "xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/resource");
        List<Path> ownerPaths = List.of(
                runtimeWorkerPackage.resolve("AdapterNodeRecord.java"),
                runtimeWorkerPackage.resolve("NodeGroupBindingRecord.java")
        );
        Map<String, Pattern> forbiddenPatterns = Map.ofEntries(
                Map.entry("eventBindings", Pattern.compile("\\beventBindings\\b")),
                Map.entry("eventCodes", Pattern.compile("\\beventCodes\\b")),
                Map.entry("EventBinding", Pattern.compile("\\bEventBinding\\b")),
                Map.entry("EventKey", Pattern.compile("\\bEventKey\\b")),
                Map.entry("WorkerCapability", Pattern.compile("\\bWorkerCapability"))
        );

        List<String> violations = new ArrayList<>();
        for (String typeName : List.of("AdapterNodeRecord", "NodeGroupBindingRecord")) {
            Path enginePath = engineWorkerPackage.resolve(typeName + ".java");
            if (Files.exists(enginePath)) {
                violations.add(enginePath + " reintroduces resource declaration record inside engine");
            }
        }
        for (Path path : ownerPaths) {
            if (!Files.isRegularFile(path)) {
                violations.add(path + " is missing");
                continue;
            }
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
        Path repo = repositoryRoot();
        Path engineWorkerPackage = repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/worker");
        Path workerRuntimePackage = repo.resolve("xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime");
        Path runtimeWorkerPackage = repo.resolve(
                "xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/resource");
        Map<Path, Pattern> forbiddenPatterns = Map.of(
                runtimeWorkerPackage.resolve("WorkerGroupRecord.java"),
                Pattern.compile("\\badapterNodeId\\b"),
                workerRuntimePackage.resolve("WorkerRegistrySnapshot.java"),
                Pattern.compile("\\bgroupIdsByAdapterNodeId\\b")
        );

        List<String> violations = new ArrayList<>();
        if (Files.exists(engineWorkerPackage.resolve("WorkerGroupRecord.java"))) {
            violations.add(engineWorkerPackage.resolve("WorkerGroupRecord.java")
                    + " reintroduces WorkerGroupRecord inside engine");
        }
        for (Map.Entry<Path, Pattern> forbiddenPattern : forbiddenPatterns.entrySet()) {
            if (!Files.isRegularFile(forbiddenPattern.getKey())) {
                violations.add(forbiddenPattern.getKey() + " is missing");
                continue;
            }
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
        Path indexPath = repositoryRoot().resolve(
                "xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/WorkerCandidateIndex.java");
        String source = Files.readString(indexPath, StandardCharsets.UTF_8);

        Map<String, Pattern> forbiddenPatterns = Map.ofEntries(
                Map.entry("supportedProjects", Pattern.compile("\\.getSupportedProjects\\s*\\(")),
                Map.entry("supportedEventCodes", Pattern.compile("\\.getSupportedEventCodes\\s*\\(")),
                Map.entry("AdapterNode", Pattern.compile("\\bAdapterNode")),
                Map.entry("NodeGroupBinding", Pattern.compile("\\bNodeGroupBinding")),
                Map.entry("WorkerManager", Pattern.compile("\\bWorkerManager\\b")),
                Map.entry("WorkerDeclarationStore", Pattern.compile("\\bWorkerDeclarationStore\\b")),
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
        if (!Pattern.compile("\\bsourceGuard\\s*\\(").matcher(source).find()) {
            violations.add(indexPath + " does not expose a source-guard owner API");
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
        Path workerManagerPath = WORKER_MANAGER_SOURCE;
        Path workerReportOwnerPath = repositoryRoot().resolve(
                "xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/WorkerReportOwner.java");
        Pattern allWorkerScan = Pattern.compile("\\.getAllWorkers\\s*\\(");

        List<String> violations = new ArrayList<>();
        for (Path path : javaSourceFiles(engineRoot)) {
            if (path.equals(workerManagerPath) || path.equals(workerReportOwnerPath)) {
                continue;
            }
            String source = Files.readString(path, StandardCharsets.UTF_8);
            if (allWorkerScan.matcher(source).find()) {
                violations.add(path + " calls WorkerDeclarationStore.getAllWorkers() outside worker resource convergence boundary");
            }
        }

        assertTrue(violations.isEmpty(),
                "WorkerDeclarationStore.getAllWorkers() is a current bootstrap/refresh residue, not a scheduling hot-path "
                        + "candidate source. New scheduling code must use WorkerManager/WorkerCandidateIndex "
                        + "until WorkerRegistry owns bounded acquisition:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerRuntimeOwnerModuleDoesNotDependOnEngine() throws IOException {
        Path runtimeRoot = repositoryRoot().resolve("xa-mass-worker-runtime/src/main/java");
        if (!Files.isDirectory(runtimeRoot)) {
            return;
        }
        Pattern engineImport = Pattern.compile("\\bcom\\.xa\\.mass\\.engine\\.");

        List<String> violations = new ArrayList<>();
        for (Path path : javaSourceFiles(runtimeRoot)) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            if (engineImport.matcher(source).find()) {
                violations.add(path + " imports engine code");
            }
        }

        assertTrue(violations.isEmpty(),
                "xa-mass-worker-runtime is an owner module below engine strategy and must not "
                        + "depend on engine internals:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerManagerCandidateReadPathDoesNotEnterRegistryLock() throws IOException {
        Path workerManagerPath = WORKER_MANAGER_SOURCE;
        String source = Files.readString(workerManagerPath, StandardCharsets.UTF_8);
        Map<String, String> guardedMethods = Map.of(
                "findWorkerCandidateBatch", "public WorkerCandidateBatch<WorkerCandidateRow> findWorkerCandidateBatch",
                "getWorkerCandidateIndex", "WorkerCandidateIndex getWorkerCandidateIndex"
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
    void taskCandidateWarmPoolDoesNotOwnEligibilityReserveOrDispatchTruth() throws IOException {
        Path warmPoolPath = Path.of("..")
                .resolve("xa-mass-worker-runtime")
                .resolve("src/main/java/com/xa/mass/worker/runtime/TaskCandidateWarmPool.java")
                .normalize();
        String source = Files.readString(warmPoolPath, StandardCharsets.UTF_8);

        Map<String, Pattern> forbiddenPatterns = Map.ofEntries(
                Map.entry("rule evaluation", Pattern.compile("\\bRuleManager\\b|\\bevaluateRules\\b|\\bRuleEvaluation\\b")),
                Map.entry("worker reserve", Pattern.compile("\\btryReserve\\b|\\bconfirmReservation\\b")),
                Map.entry("worker release/final", Pattern.compile("\\breleaseReservation\\b|\\brecordWorkFinal\\b")),
                Map.entry("dispatch runtime", Pattern.compile("\\bTaskWorkRuntime\\b|\\bTaskResultRuntime\\b|\\bTaskDispatch\\b")),
                Map.entry("route bucket acquisition", Pattern.compile("\\bacquireCandidates\\s*\\(")),
                Map.entry("worker slot truth", Pattern.compile("\\bWorkerSlot\\b|\\bWorkerRegistry\\b"))
        );

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Pattern> forbiddenPattern : forbiddenPatterns.entrySet()) {
            if (forbiddenPattern.getValue().matcher(source).find()) {
                violations.add(warmPoolPath + " owns forbidden warm-hint responsibility: "
                        + forbiddenPattern.getKey());
            }
        }

        assertTrue(violations.isEmpty(),
                "TaskCandidateWarmPool is only bounded task-local source evidence. "
                        + "It must not own rule evaluation, worker registry truth, reserve/release, "
                        + "or dispatch/result runtime state:\n"
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
        Path workerManagerPath = WORKER_MANAGER_SOURCE;
        String source = Files.readString(workerManagerPath, StandardCharsets.UTF_8);

        assertFalse(source.contains("WorkerRouteBucketOwner"),
                "WorkerManager must not keep a second route bucket membership owner. "
                        + "Stage-1 candidate membership is owned by WorkerRegistry; "
                        + "snapshot-backed route bucket code may only remain as isolated residue until removed.");
    }

    @Test
    void workerManagerReadsWorkerMembershipFromRegistryNotSnapshot() throws IOException {
        Path workerManagerPath = WORKER_MANAGER_SOURCE;
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
        Path snapshotPath = repositoryRoot().resolve(
                "xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/WorkerRegistrySnapshot.java");
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
        Path workerManagerPath = WORKER_MANAGER_SOURCE;
        String source = Files.readString(workerManagerPath, StandardCharsets.UTF_8);

        assertFalse(source.contains("workerRegistryRows"),
                "WorkerManager must not own a second mutable worker row map. "
                        + "WorkerDeclarationStore remains the current control-plane row source and "
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
        if (Pattern.compile("\\.acquireCandidates\\s*\\(").matcher(source).find()) {
            violations.add(strategyPath + " calls WorkerRegistry.acquireCandidates(...) directly");
        }
        if (Pattern.compile("\\.slotByWorkerId\\s*\\(").matcher(source).find()) {
            violations.add(strategyPath + " reads WorkerRegistry slot relation directly");
        }
        if (!Pattern.compile("\\.findWorkerCandidateBatch\\s*\\(").matcher(source).find()) {
            violations.add(strategyPath + " does not consume WorkerCandidateRuntime candidate-source API");
        }

        assertTrue(violations.isEmpty(),
                "RuleBasedTaskWorkerMatchingStrategy must consume the centralized candidate source. "
                        + "Do not reintroduce direct worker-pool scans, route-bucket reads, or source-guard "
                        + "relation checks in the rule/rank/resource path:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerAdmissionRuntimeContractLivesInWorkerRuntime() throws IOException {
        Path repo = repositoryRoot();
        Path engineContractPath = repo.resolve(
                "xa-mass-engine/src/main/java/com/xa/mass/engine/worker/WorkerAdmissionRuntime.java");
        Path runtimeContractPath = repo.resolve(
                "xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/admission/WorkerAdmissionRuntime.java");

        List<String> violations = new ArrayList<>();
        if (Files.exists(engineContractPath)) {
            violations.add(engineContractPath + " reintroduces the worker admission contract inside engine");
        }
        if (!Files.isRegularFile(runtimeContractPath)) {
            violations.add(runtimeContractPath + " is missing");
        } else {
            String source = Files.readString(runtimeContractPath, StandardCharsets.UTF_8);
            if (source.contains("com.xa.mass.engine")) {
                violations.add(runtimeContractPath + " depends on xa-mass-engine");
            }
            if (source.contains("com.xa.mass.base")) {
                violations.add(runtimeContractPath + " depends on xa-mass-base");
            }
        }

        assertTrue(violations.isEmpty(),
                "WorkerAdmissionRuntime is a worker-runtime admission contract. "
                        + "Keep it worker-runtime owned and independent from engine/base model rows:\n"
                        + String.join("\n", violations));
    }

    @Test
    void dispatchReleasePathUsesWorkerAdmissionRuntime() throws IOException {
        Map<Path, String> guardedFiles = Map.of(
                MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/listener/SimpleTaskDispatchBinder.java"),
                "dispatch binder",
                MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/resource/WorkerDispatchResourceReleaser.java"),
                "dispatch resource releaser",
                MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/listener/TaskResourceReleaseListener.java"),
                "resource release listener"
        );

        List<String> violations = new ArrayList<>();
        for (Map.Entry<Path, String> guardedFile : guardedFiles.entrySet()) {
            String source = Files.readString(guardedFile.getKey(), StandardCharsets.UTF_8);
            if (source.contains("com.xa.mass.worker.runtime.WorkerManager")
                    || Pattern.compile("\\bWorkerManager\\b").matcher(source).find()) {
                violations.add(guardedFile.getValue() + " depends on full WorkerManager");
            }
            if (!source.contains("WorkerAdmissionRuntime")) {
                violations.add(guardedFile.getValue() + " does not use WorkerAdmissionRuntime");
            }
        }

        assertTrue(violations.isEmpty(),
                "Dispatch binding and release may mutate worker occupancy only through "
                        + "WorkerAdmissionRuntime; task claim/refill stays engine-owned:\n"
                        + String.join("\n", violations));
    }

    @Test
    void taskWorkerAssignListenerConsumesRuntimeContractsForWorkerOccupancyAndWarmHints() throws IOException {
        Path listenerPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/listener/TaskWorkerAssignListener.java");
        String source = Files.readString(listenerPath, StandardCharsets.UTF_8);

        List<String> violations = new ArrayList<>();
        if (source.contains("com.xa.mass.worker.runtime.WorkerManager")
                || Pattern.compile("\\bWorkerManager\\b").matcher(source).find()) {
            violations.add(listenerPath + " depends on full WorkerManager");
        }
        if (!source.contains("WorkerAdmissionRuntime")) {
            violations.add(listenerPath + " does not use WorkerAdmissionRuntime for active occupancy/release");
        }
        if (!source.contains("WorkerWarmHintRuntime")) {
            violations.add(listenerPath + " does not use WorkerWarmHintRuntime for warm hint writes");
        }

        assertTrue(violations.isEmpty(),
                "Assignment orchestration may own allocation/refill and warm-hint timing, but worker "
                        + "occupancy and hint mutation must cross runtime contracts:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerSchedulingViewRuntimeContractLivesInWorkerRuntime() throws IOException {
        Path repo = repositoryRoot();
        Path engineContractPath = repo.resolve(
                "xa-mass-engine/src/main/java/com/xa/mass/engine/worker/WorkerSchedulingViewRuntime.java");
        Path runtimeContractPath = repo.resolve(
                "xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/evidence/WorkerSchedulingViewRuntime.java");
        Path groupCapabilityPath = repo.resolve(
                "xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/evidence/WorkerGroupCapabilityView.java");

        List<String> violations = new ArrayList<>();
        if (Files.exists(engineContractPath)) {
            violations.add(engineContractPath + " reintroduces the worker scheduling-view contract inside engine");
        }
        for (Path runtimePath : List.of(runtimeContractPath, groupCapabilityPath)) {
            if (!Files.isRegularFile(runtimePath)) {
                violations.add(runtimePath + " is missing");
                continue;
            }
            String source = Files.readString(runtimePath, StandardCharsets.UTF_8);
            if (source.contains("com.xa.mass.engine")) {
                violations.add(runtimePath + " depends on xa-mass-engine");
            }
            if (source.contains("com.xa.mass.base")) {
                violations.add(runtimePath + " depends on xa-mass-base");
            }
        }

        assertTrue(violations.isEmpty(),
                "WorkerSchedulingViewRuntime must stay worker-runtime owned and independent from "
                        + "engine-owned WorkerGroupRecord / base Worker rows:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerCapabilityReportDtosLiveInWorkerRuntimeWithoutSnapshotLeak() throws IOException {
        Path repo = repositoryRoot();
        Path engineWorkerPackage = repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/worker");
        Path workerRuntimeReportPackage = repo.resolve(
                "xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/report");
        List<String> typeNames = List.of(
                "WorkerCapabilityReport",
                "WorkerCapabilityReportResult",
                "WorkerCapabilityReportStatus",
                "WorkerReportRuntime"
        );

        List<String> violations = new ArrayList<>();
        for (String typeName : typeNames) {
            Path enginePath = engineWorkerPackage.resolve(typeName + ".java");
            Path runtimePath = workerRuntimeReportPackage.resolve(typeName + ".java");
            if (Files.exists(enginePath)) {
                violations.add(enginePath + " reintroduces capability report DTO/contract inside engine");
            }
            if (!Files.isRegularFile(runtimePath)) {
                violations.add(runtimePath + " is missing");
                continue;
            }
            String source = Files.readString(runtimePath, StandardCharsets.UTF_8);
            if (source.contains("com.xa.mass.engine")) {
                violations.add(runtimePath + " depends on xa-mass-engine");
            }
            if (source.contains("com.xa.mass.base")) {
                violations.add(runtimePath + " depends on xa-mass-base");
            }
            if (source.contains("WorkerRegistrySnapshot")) {
                violations.add(runtimePath + " exposes WorkerRegistrySnapshot");
            }
        }

        assertTrue(violations.isEmpty(),
                "Capability report DTOs and report runtime contract are runtime-facing surfaces. "
                        + "Do not leak engine WorkerRegistrySnapshot through them:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerStateReportProjectionDtosLiveInWorkerRuntime() throws IOException {
        Path repo = repositoryRoot();
        Path engineWorkerPackage = repo.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/worker");
        Path workerRuntimeReportPackage = repo.resolve(
                "xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/report");
        List<String> typeNames = List.of(
                "WorkerStateReport",
                "WorkerStateProjection",
                "WorkerStateProjectionResult",
                "WorkerStateProjectionStatus",
                "WorkerStateProjectionRuntime"
        );

        List<String> violations = new ArrayList<>();
        for (String typeName : typeNames) {
            Path enginePath = engineWorkerPackage.resolve(typeName + ".java");
            Path runtimePath = workerRuntimeReportPackage.resolve(typeName + ".java");
            if (Files.exists(enginePath)) {
                violations.add(enginePath + " reintroduces worker state DTO inside engine");
            }
            if (!Files.isRegularFile(runtimePath)) {
                violations.add(runtimePath + " is missing");
                continue;
            }
            String source = Files.readString(runtimePath, StandardCharsets.UTF_8);
            if (source.contains("com.xa.mass.engine")) {
                violations.add(runtimePath + " depends on xa-mass-engine");
            }
            if (source.contains("com.xa.mass.base")) {
                violations.add(runtimePath + " depends on xa-mass-base");
            }
        }

        assertTrue(violations.isEmpty(),
                "Worker state report/projection DTOs are worker-runtime report values. "
                        + "Keep engine state owner implementation separate from DTO ownership:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerCandidateRuntimeContractDoesNotExposeDiagnosticsOrWarmWrites() throws IOException {
        Path candidateRuntimePath = Path.of("../xa-mass-worker-runtime/src/main/java")
                .resolve("com/xa/mass/worker/runtime/candidate/WorkerCandidateRuntime.java");
        String source = Files.readString(candidateRuntimePath, StandardCharsets.UTF_8);

        List<String> violations = new ArrayList<>();
        if (source.contains("com.xa.mass.engine")) {
            violations.add(candidateRuntimePath + " depends on xa-mass-engine");
        }
        if (source.contains("com.xa.mass.base")) {
            violations.add(candidateRuntimePath + " depends on xa-mass-base");
        }
        Map<String, Pattern> forbiddenMethods = Map.ofEntries(
                Map.entry("findWorkerCandidates", Pattern.compile("\\bfindWorkerCandidates\\s*\\(")),
                Map.entry("getWorkerCandidateIndex", Pattern.compile("\\bgetWorkerCandidateIndex\\s*\\(")),
                Map.entry("recordWarmCandidate", Pattern.compile("\\brecordWarmCandidate\\s*\\("))
        );
        for (Map.Entry<String, Pattern> forbiddenMethod : forbiddenMethods.entrySet()) {
            if (forbiddenMethod.getValue().matcher(source).find()) {
                violations.add(candidateRuntimePath + " exposes " + forbiddenMethod.getKey());
            }
        }
        if (!Pattern.compile("\\bfindWorkerCandidateBatch\\s*\\(").matcher(source).find()) {
            violations.add(candidateRuntimePath + " does not expose batch candidate acquisition");
        }

        assertTrue(violations.isEmpty(),
                "WorkerCandidateRuntime is the strategy-facing candidate acquisition contract. "
                        + "Keep diagnostics and warm hint writes off candidate acquisition; warm hints "
                        + "belong on WorkerWarmHintRuntime:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerManagerCandidateAndWarmHintSurfaceStaysRuntimeNeutral() throws IOException {
        Path workerManagerPath = WORKER_MANAGER_SOURCE;
        String source = Files.readString(workerManagerPath, StandardCharsets.UTF_8);

        List<String> violations = new ArrayList<>();
        if (Pattern.compile("public\\s+void\\s+addWorker\\s*\\(\\s*Worker\\s+").matcher(source).find()) {
            violations.add(workerManagerPath + " exposes Worker-shaped resource registration");
        }
        if (Pattern.compile("public\\s+Worker\\s+getWorker\\s*\\(").matcher(source).find()) {
            violations.add(workerManagerPath + " exposes Worker-shaped resource lookup");
        }
        if (Pattern.compile("public\\s+boolean\\s+updateWorker\\s*\\(\\s*Worker\\s+").matcher(source).find()) {
            violations.add(workerManagerPath + " exposes Worker-shaped resource update");
        }
        if (Pattern.compile("public\\s+WorkerRegistrySnapshot\\s+getWorkerRegistrySnapshot\\s*\\(")
                .matcher(source)
                .find()) {
            violations.add(workerManagerPath + " exposes registry snapshot diagnostics as public API");
        }
        if (Pattern.compile("public\\s+WorkerCandidateIndex\\s+getWorkerCandidateIndex\\s*\\(")
                .matcher(source)
                .find()) {
            violations.add(workerManagerPath + " exposes candidate index diagnostics as public API");
        }
        if (Pattern.compile("public\\s+void\\s+refreshWorkerRegistrySnapshot\\s*\\(")
                .matcher(source)
                .find()) {
            violations.add(workerManagerPath + " exposes snapshot refresh diagnostics as public API");
        }
        if (Pattern.compile("\\bfindWorkerCandidateBatch\\s*\\(\\s*Task\\b").matcher(source).find()) {
            violations.add(workerManagerPath + " exposes Task-shaped candidate acquisition");
        }
        if (Pattern.compile("\\brecordWarmCandidate\\s*\\([^)]*\\bTask\\b").matcher(source).find()) {
            violations.add(workerManagerPath + " exposes Task-shaped warm hint mutation");
        }
        if (Pattern.compile("\\brecordWarmCandidate\\s*\\([^)]*\\bWorker\\b").matcher(source).find()) {
            violations.add(workerManagerPath + " exposes Worker-shaped warm hint mutation");
        }

        assertTrue(violations.isEmpty(),
                "WorkerManager is still assembly, but resource, candidate acquisition, and warm-hint "
                        + "entrypoints must stay on model-neutral WorkerResourceRecord / "
                        + "WorkerTaskSelector / WorkerCandidateRow shapes:\n"
                        + String.join("\n", violations));
    }

    @Test
    void ruleBasedMatchingDoesNotInferSchedulingViewRuntimeFromCandidateRuntime() throws IOException {
        Path strategyPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/strategy/RuleBasedTaskWorkerMatchingStrategy.java");
        String source = Files.readString(strategyPath, StandardCharsets.UTF_8);

        List<String> violations = new ArrayList<>();
        if (source.contains("com.xa.mass.worker.runtime.WorkerManager")
                || Pattern.compile("\\bWorkerManager\\b").matcher(source).find()) {
            violations.add(strategyPath + " depends on full WorkerManager");
        }
        if (source.contains("candidateRuntime instanceof WorkerSchedulingViewRuntime")) {
            violations.add(strategyPath + " infers scheduling-view runtime from candidate runtime");
        }
        if (source.contains("must also implement WorkerSchedulingViewRuntime")) {
            violations.add(strategyPath + " requires candidate runtime to also implement scheduling-view runtime");
        }

        assertTrue(violations.isEmpty(),
                "Candidate acquisition and scheduling-view reads are separate worker runtime surfaces. "
                        + "The strategy must consume explicit runtime contracts and must not depend on "
                        + "the WorkerManager assembly surface:\n"
                        + String.join("\n", violations));
    }

    @Test
    void engineStrategyConsumesWorkerRuntimeViewsWithoutWorkerRuntimeOwnerAccess() throws IOException {
        Path strategyRoot = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/strategy");
        Map<String, Pattern> forbiddenPatterns = Map.ofEntries(
                Map.entry("worker-runtime non-match package",
                        Pattern.compile("\\bimport\\s+com\\.xa\\.mass\\.worker\\.runtime\\.(?!(?:candidate|evidence|admission|routing)\\.)")),
                Map.entry("worker registry contract",
                        Pattern.compile("\\bWorkerRegistry\\b")),
                Map.entry("low-level worker registry primitive import",
                        Pattern.compile("\\bimport\\s+com\\.xa\\.mass\\.runtime\\.worker\\."
                                + "(?:WorkerRegistry|WorkerSlot|WorkerMeta|WorkerRouteBucketPolicy)\\b")),
                Map.entry("resource/report/group runtime owners",
                        Pattern.compile("\\bWorker(?:Resource|Report|Group|Relationship|Admission|CandidateSource)Owner\\b")),
                Map.entry("state projection owner",
                        Pattern.compile("\\bWorkerStateProjectionOwner\\b")),
                Map.entry("resource/report/gate mutation contracts",
                        Pattern.compile("\\bWorker(?:Resource|Report|DispatchGate|WarmHint)Runtime\\b"))
        );

        List<String> violations = new ArrayList<>();
        for (Path sourcePath : javaSourceFiles(strategyRoot)) {
            String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
            for (Map.Entry<String, Pattern> forbiddenPattern : forbiddenPatterns.entrySet()) {
                if (forbiddenPattern.getValue().matcher(source).find()) {
                    violations.add(sourcePath + " reaches worker runtime ownership: " + forbiddenPattern.getKey());
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Engine matching strategy may consume candidate/admission/scheduling-view evidence, "
                        + "but must not own worker registry/resource/report/gate mutation truth:\n"
                        + String.join("\n", violations));
    }

    @Test
    void massRuntimeApiWorkerPackageContainsOnlyLowLevelRegistrySpi() throws IOException {
        Path workerApiPackage = repositoryRoot().resolve(
                "platform_infra/mass-runtime-api/src/main/java/com/xa/mass/runtime/worker");
        Set<String> allowedFiles = Set.of(
                "CleanupSummary.java",
                "DefaultWorkerRouteBucketPolicy.java",
                "DispatchAvailabilitySource.java",
                "EventKey.java",
                "RandomWorkerCandidateSamplingPolicy.java",
                "ReserveResult.java",
                "ReserveStatus.java",
                "WorkerCandidateSamplingContext.java",
                "WorkerCandidateSamplingPolicy.java",
                "WorkerMeta.java",
                "WorkerRegistry.java",
                "WorkerRouteBucketPolicy.java",
                "WorkerSlot.java"
        );
        Set<String> actualFiles = javaSourceFiles(workerApiPackage).stream()
                .map(path -> path.getFileName().toString())
                .collect(java.util.stream.Collectors.toSet());

        List<String> violations = new ArrayList<>();
        for (String actualFile : actualFiles) {
            if (!allowedFiles.contains(actualFile)) {
                violations.add("unexpected worker runtime-api type: " + actualFile);
            }
        }
        for (String allowedFile : allowedFiles) {
            if (!actualFiles.contains(allowedFile)) {
                violations.add("missing allowed worker runtime-api type: " + allowedFile);
            }
        }

        assertTrue(violations.isEmpty(),
                "mass-runtime-api worker package must remain a low-level registry SPI allowlist:\n"
                        + String.join("\n", violations));
    }

    @Test
    void registryImplementationsDoNotDependOnWorkerRuntimeOwnerModule() throws IOException {
        Path repo = repositoryRoot();
        List<Path> registryImplementationRoots = List.of(
                repo.resolve("platform_infra/mass-runtime-memory/src/main/java"),
                repo.resolve("platform_infra/mass-runtime-redis/src/main/java")
        );
        Pattern workerRuntimeImport = Pattern.compile("\\bimport\\s+com\\.xa\\.mass\\.worker\\.runtime\\.");

        List<String> violations = new ArrayList<>();
        for (Path root : registryImplementationRoots) {
            for (Path sourcePath : javaSourceFiles(root)) {
                String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
                if (workerRuntimeImport.matcher(source).find()) {
                    violations.add(sourcePath + " imports worker-runtime owner module");
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Memory/Redis registry implementations may depend on mass-runtime-api only, "
                        + "not xa-mass-worker-runtime owner contracts:\n"
                        + String.join("\n", violations));
    }

    @Test
    void transportWorkerRuntimeAccessStaysLookupOnly() throws IOException {
        Path repo = repositoryRoot();
        List<Path> transportRoots = List.of(
                repo.resolve("transport/transport_runtime/src/main/java"),
                repo.resolve("transport/polling-adapter/src/main/java"),
                repo.resolve("transport/socket-adapter/src/main/java"),
                repo.resolve("transport/websocket-adapter/src/main/java")
        );
        Map<String, Pattern> forbiddenPatterns = Map.ofEntries(
                Map.entry("full resource mutation surface",
                        Pattern.compile("\\bimport\\s+com\\.xa\\.mass\\.worker\\.runtime\\.resource\\."
                                + "(?:WorkerResourceRuntime|WorkerResourceDeclarationRuntime|WorkerNodeBindingRuntime)\\b")),
                Map.entry("worker registry mutation surface",
                        Pattern.compile("\\bimport\\s+com\\.xa\\.mass\\.runtime\\.worker\\.WorkerRegistry\\b")),
                Map.entry("admission/control/report mutation surface",
                        Pattern.compile("\\bimport\\s+com\\.xa\\.mass\\.worker\\.runtime\\."
                                + "(?:admission\\.WorkerAdmissionRuntime|control\\.WorkerDispatchGateRuntime|"
                                + "report\\.WorkerReportRuntime|admission\\.WorkerWarmHintRuntime)\\b"))
        );

        List<String> violations = new ArrayList<>();
        for (Path root : transportRoots) {
            for (Path sourcePath : javaSourceFiles(root)) {
                String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
                for (Map.Entry<String, Pattern> forbiddenPattern : forbiddenPatterns.entrySet()) {
                    if (forbiddenPattern.getValue().matcher(source).find()) {
                        violations.add(sourcePath + " reaches " + forbiddenPattern.getKey());
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Transport may consume worker resource lookup evidence, but must not mutate "
                        + "worker runtime state or registry truth directly:\n"
                        + String.join("\n", violations));
    }

    @Test
    void serverAndTransportDoNotImportEngineWorkerInternals() throws IOException {
        Path repo = repositoryRoot();
        List<Path> guardedRoots = List.of(
                repo.resolve("xa-mass-server/src/main/java"),
                repo.resolve("transport/transport_runtime/src/main/java"),
                repo.resolve("transport/polling-adapter/src/main/java"),
                repo.resolve("transport/socket-adapter/src/main/java"),
                repo.resolve("transport/websocket-adapter/src/main/java")
        );
        Pattern engineWorkerImport = Pattern.compile("\\bimport\\s+com\\.xa\\.mass\\.engine\\.worker\\.");

        List<String> violations = new ArrayList<>();
        for (Path root : guardedRoots) {
            for (Path sourcePath : javaSourceFiles(root)) {
                String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
                if (engineWorkerImport.matcher(source).find()) {
                    violations.add(sourcePath + " imports engine worker internals");
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Server and transport runtime code must use SDK/runtime contracts, "
                        + "not engine worker internals:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerManagerDoesNotUseWorkerLevelEventStorageIndexForEventCandidateSource() throws IOException {
        Path workerManagerPath = WORKER_MANAGER_SOURCE;
        Path candidateSourceOwnerPath = repositoryRoot().resolve(
                "xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/WorkerCandidateSourceOwner.java");
        String source = Files.readString(workerManagerPath, StandardCharsets.UTF_8)
                + "\n"
                + Files.readString(candidateSourceOwnerPath, StandardCharsets.UTF_8);

        List<String> violations = new ArrayList<>();
        if (Pattern.compile("\\.getWorkersBySupportedEventCode\\s*\\(").matcher(source).find()) {
            violations.add("worker candidate source calls worker-level supported-event storage index");
        }
        if (Pattern.compile("\\.getWorkersBySupportedProject\\s*\\(").matcher(source).find()) {
            violations.add("worker candidate source calls worker-level supported-project storage index");
        }
        if (!Pattern.compile("\\bWorkerCandidateIndex\\b").matcher(source).find()
                || !Pattern.compile("\\.workersFor\\s*\\(").matcher(source).find()) {
            violations.add(candidateSourceOwnerPath + " does not use WorkerCandidateIndex for indexed candidate lookup");
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
                "xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/resource/WorkerDeclarationStore.java");
        String source = Files.readString(workerStoragePath, StandardCharsets.UTF_8);

        List<String> violations = new ArrayList<>();
        if (Pattern.compile("\\bgetWorkersBySupportedEventCode\\s*\\(").matcher(source).find()) {
            violations.add(workerStoragePath + " exposes supported-event candidate lookup");
        }
        if (Pattern.compile("\\bgetWorkersBySupportedProject\\s*\\(").matcher(source).find()) {
            violations.add(workerStoragePath + " exposes supported-project candidate lookup");
        }

        assertTrue(violations.isEmpty(),
                "WorkerDeclarationStore must stay runtime worker-registry storage. Capability candidate "
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
        if (Files.exists(jdbcSourceRoot.resolve("JdbcWorkerDeclarationStore.java"))) {
            violations.add(jdbcSourceRoot.resolve("JdbcWorkerDeclarationStore.java")
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
        Path candidateIndexPath = repositoryRoot().resolve(
                "xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/WorkerCandidateIndex.java");
        Path workerManagerPath = WORKER_MANAGER_SOURCE;
        Path binderPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/listener/SimpleTaskDispatchBinder.java");
        Path traceLoggerPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/TraceEventLogger.java");
        Path ruleConfigPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/rules/RuleConfig.java");
        Path assignmentListenerPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/listener/TaskWorkerAssignListener.java");
        Path allocationRequestPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/assignment/AssignmentAllocationRequest.java");

        String candidateIndex = Files.readString(candidateIndexPath, StandardCharsets.UTF_8);
        String findWorkerCandidateBatch = sourceMethod(
                Files.readString(workerManagerPath, StandardCharsets.UTF_8),
                "public WorkerCandidateBatch<WorkerCandidateRow> findWorkerCandidateBatch"
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
        if (Pattern.compile("\\.getAllWorkers\\s*\\(").matcher(findWorkerCandidateBatch).find()) {
            violations.add(workerManagerPath + "#findWorkerCandidateBatch falls back to all workers");
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
        if (ruleConfig.contains("appCount") || ruleConfig.contains("agentVersion")) {
            violations.add(ruleConfigPath + " keeps aggregate worker metadata as default eligibility truth");
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
        Path root = repositoryRoot();
        Path workerStoragePath = root.resolve(
                "xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/resource/WorkerDeclarationStore.java");
        Path memoryStoragePath = root.resolve(
                "platform_infra/mass-storage-memory/src/main/java/com/xa/mass/storage/memory/InMemoryWorkerDeclarationStore.java");
        Path sdkDiagnosticsPath = root.resolve(
                "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/DefaultRuntimeDiagnosticsOperations.java");

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
        if (sdkDiagnostics.contains("getWorkerDeclarationStore().isLocked")) {
            violations.add(sdkDiagnosticsPath + " reads lock truth from WorkerDeclarationStore");
        }

        assertTrue(violations.isEmpty(),
                "WorkerDeclarationStore is control-plane worker row storage only. Runtime exclusive lease truth "
                        + "must stay in WorkerRegistry/WorkerManager:\n"
                        + String.join("\n", violations));
    }

    @Test
    void productionDoesNotWireWorkerLoadViewAsRuntimeOccupancyOwner() throws IOException {
        Path workerManagerPath = WORKER_MANAGER_SOURCE;
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
                Path.of("..", "sdk", "xa-mass-embedded-sdk", "src", "main", "java", "com", "xa", "mass", "starter", "config",
                        "EngineConfig.java"),
                Path.of("..", "sdk", "xa-mass-embedded-sdk", "src", "main", "java", "com", "xa", "mass", "starter", "builder",
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
                WORKER_MANAGER_SOURCE,
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
    void dispatchAvailabilityPolicyConsumesOnlyDispatchGateRuntime() throws IOException {
        Path policyPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/control/WorkerDispatchAvailabilityPolicy.java");
        Path defaultPolicyPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/control/DefaultWorkerDispatchAvailabilityPolicy.java");
        Path controlServicePath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/control/WorkerControlService.java");
        String policySource = Files.readString(policyPath, StandardCharsets.UTF_8)
                + "\n"
                + Files.readString(defaultPolicyPath, StandardCharsets.UTF_8);
        String controlServiceSource = Files.readString(controlServicePath, StandardCharsets.UTF_8);

        List<String> violations = new ArrayList<>();
        if (Pattern.compile("\\bWorkerManager\\b").matcher(policySource).find()) {
            violations.add("dispatch availability policy reintroduced full WorkerManager access");
        }
        if (!policySource.contains("WorkerDispatchGateRuntime")) {
            violations.add("dispatch availability policy does not consume WorkerDispatchGateRuntime");
        }
        if (Pattern.compile("\\bWorkerManager\\b").matcher(controlServiceSource).find()) {
            violations.add("WorkerControlService depends on full WorkerManager instead of narrow runtime contracts");
        }
        if (Pattern.compile("\\bWorkerStateProjectionOwner\\b").matcher(controlServiceSource).find()) {
            violations.add("WorkerControlService depends on WorkerStateProjectionOwner instead of runtime contract");
        }
        if (Pattern.compile("import\\s+com\\.xa\\.mass\\.worker\\.runtime\\.(?!(?:resource|report|control|command)\\.)")
                .matcher(controlServiceSource)
                .find()) {
            violations.add("WorkerControlService depends on worker-runtime implementation package instead of worker-runtime contracts");
        }
        for (String requiredContract : List.of(
                "WorkerReportRuntime",
                "WorkerResourceQueryRuntime",
                "WorkerDispatchGateRuntime",
                "WorkerStateProjectionRuntime")) {
            if (!controlServiceSource.contains(requiredContract)) {
                violations.add("WorkerControlService does not consume " + requiredContract);
            }
        }

        assertTrue(violations.isEmpty(),
                "Worker-control policy may mutate dispatch eligibility only through "
                        + "WorkerDispatchGateRuntime:\n"
                        + String.join("\n", violations));
    }

    @Test
    void sdkApplicationUsesWorkerRuntimeAccessorsForWorkerShellOperations() throws IOException {
        Path sdkApplicationPath = Path.of("..", "sdk", "xa-mass-embedded-sdk", "src", "main", "java",
                "com", "xa", "mass", "sdk", "MassSdkApplication.java");
        Path diagnosticsPath = Path.of("..", "sdk", "xa-mass-embedded-sdk", "src", "main", "java",
                "com", "xa", "mass", "sdk", "DefaultRuntimeDiagnosticsOperations.java");
        Path massEnginePath = Path.of("..", "sdk", "xa-mass-embedded-sdk", "src", "main", "java",
                "com", "xa", "mass", "starter", "MassEngine.java");
        Path runtimeKernelPath = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/EngineRuntimeKernel.java");
        Path engineConfigPath = Path.of("..", "sdk", "xa-mass-embedded-sdk", "src", "main", "java",
                "com", "xa", "mass", "starter", "config", "EngineConfig.java");
        String sdkSource = Files.readString(sdkApplicationPath, StandardCharsets.UTF_8);
        String diagnosticsSource = Files.readString(diagnosticsPath, StandardCharsets.UTF_8);
        String massEngineSource = Files.readString(massEnginePath, StandardCharsets.UTF_8);
        String runtimeKernelSource = Files.readString(runtimeKernelPath, StandardCharsets.UTF_8);
        String engineConfigSource = Files.readString(engineConfigPath, StandardCharsets.UTF_8);

        List<String> violations = new ArrayList<>();
        if (sdkSource.contains("getWorkerManager()")) {
            violations.add(sdkApplicationPath + " calls EngineConfig.getWorkerManager()");
        }
        if (sdkSource.contains("com.xa.mass.worker.runtime.resource.WorkerDeclarationStore")
                || Pattern.compile("\\bWorkerDeclarationStore\\b").matcher(sdkSource).find()
                || sdkSource.contains("getWorkerDeclarationStore()")) {
            violations.add(sdkApplicationPath + " uses WorkerDeclarationStore for SDK worker shell operations");
        }
        if (!sdkSource.contains("WorkerResourceRuntime")) {
            violations.add(sdkApplicationPath + " does not use WorkerResourceRuntime");
        }
        if (sdkSource.contains("com.xa.mass.engine.worker.WorkerControlService")
                || Pattern.compile("\\bWorkerControlService\\b").matcher(sdkSource).find()) {
            violations.add(sdkApplicationPath + " imports WorkerControlService instead of WorkerControlRuntime");
        }
        if (sdkSource.contains("requireStartedWorkerControlService")) {
            violations.add(sdkApplicationPath + " names WorkerControlRuntime access as service");
        }
        if (diagnosticsSource.contains("getWorkerManager()")) {
            violations.add(diagnosticsPath + " calls EngineConfig.getWorkerManager()");
        }
        if (massEngineSource.contains("getWorkerManager()")) {
            violations.add(massEnginePath + " calls EngineConfig.getWorkerManager()");
        }
        if (Pattern.compile("public\\s+WorkerManager\\s+getWorkerManager\\s*\\(")
                .matcher(engineConfigSource)
                .find()) {
            violations.add(engineConfigPath + " exposes WorkerManager as public starter config surface");
        }
        if (!engineConfigSource.contains("WorkerControlRuntime getWorkerControlRuntime()")) {
            violations.add(engineConfigPath + " does not expose worker control through WorkerControlRuntime");
        }
        if (massEngineSource.contains("com.xa.mass.worker.runtime.WorkerManager")
                || Pattern.compile("\\bWorkerManager\\b").matcher(massEngineSource).find()) {
            violations.add(massEnginePath + " depends on full WorkerManager");
        }
        if (!runtimeKernelSource.contains("getWorkerAvailabilityWakeupRuntime()")) {
            violations.add(runtimeKernelPath + " does not use WorkerAvailabilityWakeupRuntime");
        }

        assertTrue(violations.isEmpty(),
                "SDK worker shell operations should use EngineConfig's narrow worker runtime "
                        + "accessors instead of full WorkerManager:\n"
                        + String.join("\n", violations));
    }

    @Test
    void sdkRuntimeBridgeDoesNotRequireFullWorkerManager() throws IOException {
        List<Path> bridgePaths = List.of(
                Path.of("..", "sdk", "xa-mass-embedded-sdk", "src", "main", "java",
                        "com", "xa", "mass", "starter", "EngineRuntimeBridge.java"),
                Path.of("..", "sdk", "xa-mass-embedded-sdk", "src", "main", "java",
                        "com", "xa", "mass", "starter", "RuntimeEventBusEngineBridge.java")
        );

        List<String> violations = new ArrayList<>();
        for (Path bridgePath : bridgePaths) {
            String source = Files.readString(bridgePath, StandardCharsets.UTF_8);
            if (source.contains("com.xa.mass.worker.runtime.WorkerManager")
                    || Pattern.compile("\\bWorkerManager\\b").matcher(source).find()) {
                violations.add(bridgePath + " depends on full WorkerManager");
            }
            if (!source.contains("WorkerResourceRuntime")) {
                violations.add(bridgePath + " does not use WorkerResourceRuntime");
            }
        }

        assertTrue(violations.isEmpty(),
                "SDK runtime bridge is shell wiring for legacy heartbeat refresh. It must depend on "
                        + "WorkerResourceRuntime instead of full WorkerManager:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerControlKernelEventsDoNotUseWorkerManagerEntryNames() throws IOException {
        Path registryPath = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/event/KernelEventHandlerRegistry.java");
        List<Path> workerControlHandlerPaths = List.of(
                MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/command/WorkerCommandRequestEventHandler.java"),
                MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/control/WorkerCapabilityReportEventHandler.java"),
                MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/control/WorkerStateReportEventHandler.java")
        );
        List<Path> workerControlEventPaths = new ArrayList<>();
        workerControlEventPaths.add(registryPath);
        workerControlEventPaths.addAll(workerControlHandlerPaths);

        List<String> violations = new ArrayList<>();
        for (Path path : workerControlEventPaths) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            if (source.contains("registerWorkerManagerEvent")
                    || source.contains("registerOrReplaceWorkerManagerEvent")) {
                violations.add(path + " uses WorkerManager event registration naming");
            }
        }
        for (Path path : workerControlHandlerPaths) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            if (source.contains("WorkerControlService")) {
                violations.add(path + " depends on WorkerControlService instead of WorkerControlRuntime");
            }
            if (!source.contains("WorkerControlRuntime")) {
                violations.add(path + " does not consume WorkerControlRuntime");
            }
        }

        assertTrue(violations.isEmpty(),
                "Kernel worker-control event registration must not name WorkerManager as the owner, "
                        + "and handlers must consume WorkerControlRuntime rather than the concrete service:\n"
                        + String.join("\n", violations));
    }

    @Test
    void taskSelectorAndRoutePolicyAdaptersStayInEngineStrategyPackage() throws IOException {
        Path workerPackage = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/worker");
        Path strategyPackage = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/strategy");

        List<String> violations = new ArrayList<>();
        for (String adapterFile : List.of("WorkerTaskSelectorFactory.java", "WorkerRoutingPolicy.java")) {
            if (Files.exists(workerPackage.resolve(adapterFile))) {
                violations.add(workerPackage.resolve(adapterFile)
                        + " keeps task selector or route policy adapter in worker package");
            }
            if (!Files.exists(strategyPackage.resolve(adapterFile))) {
                violations.add(strategyPackage.resolve(adapterFile)
                        + " is missing engine strategy selector/routing adapter");
            }
        }

        assertTrue(violations.isEmpty(),
                "Task sharedConfig to WorkerTaskSelector adaptation and route-bucket policy are "
                        + "engine strategy concerns, not worker runtime owner residue:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerResourceRuntimeContractDoesNotExposeBaseWorkerModel() throws IOException {
        Path engineContractPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/worker/WorkerResourceRuntime.java");
        Path lookupStorePath = Path.of("..", "platform_infra", "mass-storage-api", "src", "main", "java",
                "com", "xa", "mass", "storage", "api", "WorkerLookupStore.java");
        Path runtimeContractPath = Path.of("..", "xa-mass-worker-runtime", "src", "main", "java",
                "com", "xa", "mass", "worker", "runtime", "resource", "WorkerResourceRuntime.java");
        Path runtimeRecordPath = Path.of("..", "xa-mass-worker-runtime", "src", "main", "java",
                "com", "xa", "mass", "worker", "runtime", "resource", "WorkerResourceRecord.java");

        List<String> violations = new ArrayList<>();
        if (Files.exists(engineContractPath)) {
            violations.add(engineContractPath + " still exists as an engine-local resource runtime contract");
        }
        if (Files.exists(lookupStorePath)) {
            violations.add(lookupStorePath + " reintroduces a storage-edge worker lookup seam");
        }
        String runtimeContract = Files.readString(runtimeContractPath, StandardCharsets.UTF_8);
        String runtimeRecord = Files.readString(runtimeRecordPath, StandardCharsets.UTF_8);
        String combined = runtimeContract + "\n" + runtimeRecord;
        if (combined.contains("com.xa.mass.engine")) {
            violations.add("worker resource runtime contract imports engine types");
        }
        if (combined.contains("com.xa.mass.base.model.Worker")) {
            violations.add("worker resource runtime contract exposes base.model.Worker");
        }

        assertTrue(violations.isEmpty(),
                "WorkerResourceRuntime is a worker-runtime resource boundary and must not expose base.model.Worker:\n"
                        + String.join("\n", violations));
    }

    @Test
    void inMemoryWorkerRegistryImplementationLivesInRuntimeMemory() throws IOException {
        Path enginePath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/worker/InMemoryWorkerRegistry.java");
        Path runtimePath = Path.of("..", "platform_infra", "mass-runtime-memory", "src", "main", "java",
                "com", "xa", "mass", "runtime", "memory", "InMemoryWorkerRegistry.java");

        List<String> violations = new ArrayList<>();
        if (Files.exists(enginePath)) {
            violations.add(enginePath + " reintroduces the memory WorkerRegistry implementation inside engine");
        }
        if (!Files.isRegularFile(runtimePath)) {
            violations.add(runtimePath + " is missing");
        } else {
            String source = Files.readString(runtimePath, StandardCharsets.UTF_8);
            if (source.contains("com.xa.mass.engine")) {
                violations.add(runtimePath + " depends on xa-mass-engine");
            }
            if (!source.contains("implements WorkerRegistry")) {
                violations.add(runtimePath + " no longer implements WorkerRegistry");
            }
        }
        for (Path path : javaSourceFiles(MAIN_SOURCE_ROOT)) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            if (source.contains("InMemoryWorkerRegistry")) {
                violations.add(path + " depends on the memory WorkerRegistry implementation");
            }
        }

        assertTrue(violations.isEmpty(),
                "The in-memory worker slot/index/admission implementation belongs in mass-runtime-memory. "
                        + "SDK/server assembly may inject it, but engine main must not own or instantiate it:\n"
                        + String.join("\n", violations));
    }

    @Test
    void workerRegistryImplementationsDoNotImportEngineWorkerPolicy() throws IOException {
        Path repo = repositoryRoot();
        List<Path> roots = List.of(
                repo.resolve("platform_infra/mass-runtime-memory/src/main/java"),
                repo.resolve("platform_infra/mass-runtime-memory/src/test/java"),
                repo.resolve("platform_infra/mass-runtime-redis/src/main/java"),
                repo.resolve("platform_infra/mass-runtime-redis/src/test/java")
        );

        List<String> violations = new ArrayList<>();
        for (Path root : roots) {
            for (Path path : javaSourceFiles(root)) {
                String source = Files.readString(path, StandardCharsets.UTF_8);
                if (source.contains("com.xa.mass.engine.worker")
                        || Pattern.compile("\\bWorkerRoutingPolicy\\b").matcher(source).find()) {
                    violations.add(path + " imports or references engine worker routing policy");
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "WorkerRegistry implementations must consume runtime-api route-bucket policy, "
                        + "not engine worker policy:\n"
                        + String.join("\n", violations));
    }

    @Test
    void taskWriteLockRemainsLifecycleAndProgressOnly() throws IOException {
        Path taskManagerPath = MAIN_SOURCE_ROOT.resolve("com/xa/mass/engine/TaskManager.java");
        String source = Files.readString(taskManagerPath, StandardCharsets.UTF_8);
        Path concurrencyCoordinatorPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/LocalTaskConcurrencyCoordinator.java");
        String concurrencyCoordinatorSource = Files.readString(concurrencyCoordinatorPath, StandardCharsets.UTF_8);

        List<String> approvedTaskWriteLockMethods = List.of(
                "public boolean deleteTask(String taskId)",
                "public boolean patchTaskDefinition(String taskId, TaskDefinitionPatch patch)",
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

        Map<String, String> runtimeOwnedMethods = Map.of(
                "claimReady",
                sourceMethod(source, "public List<ClaimedTaskWork> claimReady"),
                "applyTaskWorkResultWithContext",
                sourceMethod(source, "RuntimeResultApplyContext applyTaskWorkResultWithContext")
        );
        for (Map.Entry<String, String> runtimeOwnedMethod : runtimeOwnedMethods.entrySet()) {
            for (String forbidden : List.of("withTaskLock(", "withTaskWorkReadLock(", "withTaskWriteLock(")) {
                if (runtimeOwnedMethod.getValue().contains(forbidden)) {
                    violations.add(taskManagerPath + " " + runtimeOwnedMethod.getKey()
                            + " must stay runtime-owned and not take task locks: " + forbidden);
                }
            }
        }

        String workReadLock = sourceMethod(
                concurrencyCoordinatorSource,
                "public <T> T withTaskWorkReadLock"
        );
        if (workReadLock.contains("writeLock()") || workReadLock.contains("withTaskWriteLock(")) {
            violations.add(concurrencyCoordinatorPath
                    + " withTaskWorkReadLock must remain a task read/message guard, not a task write lock");
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
                                        repo.resolve("xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/evidence/WorkerReachabilityView.java"),
                                        repo.resolve("xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/WorkerManager.java")
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
                                        repo.resolve("xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/evidence/WorkerReachabilityView.java")
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
                "\\bimport\\s+com\\.xa\\.mass\\.runtime\\.api\\."
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
                repo.resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/MassSdkApplication.java"),
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
    void workerCapabilityReportHandlerStaysInWorkerControlEventBoundary() throws IOException {
        Path handlerPath = MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/engine/control/WorkerCapabilityReportEventHandler.java");
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
                        + "to WorkerControlRuntime only. It must not become "
                        + "transport presence, task result, matching, reachability, or load ownership:\n"
                        + String.join("\n", violations));
    }

    private static List<Path> selectedSuiteSourceFiles() {
        SelectClasses selectedClasses = EngineSchedulingCoreSuite.class.getAnnotation(SelectClasses.class);
        assertNotNull(selectedClasses, "EngineSchedulingCoreSuite must declare @SelectClasses");
        List<Path> sourceFiles = Arrays.stream(selectedClasses.value())
                .filter(Objects::nonNull)
                .filter(testClass -> testClass != EngineSchedulingCoreArchitectureGuardTest.class)
                .filter(testClass -> testClass != EngineProofOwnershipGuardTest.class)
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

    private static List<Path> sourceOrSingleFile(Path root) throws IOException {
        if (Files.isRegularFile(root)) {
            return List.of(root);
        }
        return javaSourceFiles(root);
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
                repo.resolve("sdk/xa-mass-embedded-sdk-api/src/main/java"),
                repo.resolve("xa-mass-base/src/main/java"),
                repo.resolve("xa-mass-engine/src/main/java"),
                repo.resolve("sdk/xa-mass-embedded-sdk/src/main/java"),
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
