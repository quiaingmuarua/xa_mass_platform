package com.xa.mass.starter;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MassEngineAssemblyBoundaryTest {

    private static final Set<String> CLASSIFIED_ENGINE_IMPORTS = Set.of(
            "import com.xa.mass.engine.EngineRuntimeKernel;",
            "import com.xa.mass.engine.EngineRuntimeKernelConfig;",
            "import com.xa.mass.engine.ExponentialPollingIdleBackoffPolicy;",
            "import com.xa.mass.engine.PollingIdleBackoffPolicy;",
            "import com.xa.mass.engine.TaskAssignmentRuntimePort;",
            "import com.xa.mass.engine.TaskCommandService;",
            "import com.xa.mass.engine.TaskDispatchWakeupPort;",
            "import com.xa.mass.engine.TaskEventListenerRegistrar;",
            "import com.xa.mass.engine.TaskEventService;",
            "import com.xa.mass.engine.TaskLeaseMaintenancePort;",
            "import com.xa.mass.engine.TaskManager;",
            "import com.xa.mass.engine.TaskManagerResultIngestFacade;",
            "import com.xa.mass.engine.TaskQueryService;",
            "import com.xa.mass.engine.TaskRuntimeRecoveryPort;",
            "import com.xa.mass.engine.TaskShellLifecycleMaintenancePort;",
            "import com.xa.mass.engine.TraceEventLogger;",
            "import com.xa.mass.engine.WorkerControlRuntime;",
            "import com.xa.mass.worker.runtime.command.WorkerCommandAcknowledgement;",
            "import com.xa.mass.worker.runtime.command.WorkerCommandDeliveryResult;",
            "import com.xa.mass.worker.runtime.command.WorkerCommandLifecycleOwner;",
            "import com.xa.mass.worker.runtime.command.WorkerCommandLifecycleResult;",
            "import com.xa.mass.worker.runtime.command.WorkerCommandRecord;",
            "import com.xa.mass.worker.runtime.command.WorkerCommandRequest;",
            "import com.xa.mass.worker.runtime.command.WorkerCommandStatus;",
            "import com.xa.mass.engine.control.DefaultWorkerDispatchAvailabilityPolicy;",
            "import com.xa.mass.engine.control.WorkerControlService;",
            "import com.xa.mass.engine.control.WorkerDispatchAvailabilityPolicy;",
            "import com.xa.mass.engine.model.TaskAppendReceipt;",
            "import com.xa.mass.engine.model.TaskDefinitionPatch;",
            "import com.xa.mass.engine.model.TaskResumeResult;",
            "import com.xa.mass.engine.model.TaskStateResolutionResult;",
            "import com.xa.mass.engine.model.TaskStateValidationResult;",
            "import com.xa.mass.engine.policy.ContractAwareTaskTerminalPolicy;",
            "import com.xa.mass.engine.rules.MatchingRuleEvaluator;",
            "import com.xa.mass.engine.rules.MatchingRuleSetProvider;",
            "import com.xa.mass.engine.rules.RegistryBackedMatchingRuleEvaluator;",
            "import com.xa.mass.engine.rules.RuleConfig;",
            "import com.xa.mass.engine.rules.RuleEvaluatorRegistries;",
            "import com.xa.mass.engine.rules.RuleEvaluatorRegistry;",
            "import com.xa.mass.engine.service.AssignmentDiagnosticRecorder;",
            "import com.xa.mass.engine.service.AssignmentRecordService;",
            "import com.xa.mass.engine.stage.TaskStageEvidenceOwner;",
            "import com.xa.mass.engine.stage.TaskStageEvidenceResult;",
            "import com.xa.mass.engine.stage.TaskStageEvidenceService;",
            "import com.xa.mass.engine.stage.TaskStageProjection;"
    );

    @Test
    void sdkMainDoesNotImportEngineRuntimeImplementationPackages() throws IOException {
        List<String> forbiddenReferences = List.of(
                "import com.xa.mass.engine.listener.",
                "import com.xa.mass.engine.util.",
                "import com.xa.mass.engine.watchdog.",
                "com.xa.mass.engine.listener.",
                "com.xa.mass.engine.util.",
                "com.xa.mass.engine.watchdog."
        );
        List<String> violations = Files.walk(Path.of("src/main/java"))
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .flatMap(path -> forbiddenReferences.stream()
                        .filter(forbidden -> contains(path, forbidden))
                        .map(forbidden -> path + " imports forbidden engine implementation package: " + forbidden))
                .toList();

        assertTrue(violations.isEmpty(),
                "SDK main code must not import engine listener/watchdog/util implementation packages. "
                        + "Default runtime assembly belongs inside EngineRuntimeKernel, and MDC cleanup "
                        + "should use SDK-local or logging-library APIs:\n"
                        + String.join("\n", violations));
    }

    @Test
    void sdkMainEngineImportsAreExplicitlyClassified() throws IOException {
        Set<String> violations = new LinkedHashSet<>();
        Files.walk(Path.of("src/main/java"))
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> engineImports(path).stream()
                        .filter(importLine -> !CLASSIFIED_ENGINE_IMPORTS.contains(importLine))
                        .map(importLine -> path + " has unclassified engine import: " + importLine)
                        .forEach(violations::add));

        assertTrue(violations.isEmpty(),
                "Every SDK main import from com.xa.mass.engine must be classified in "
                        + "ENGINE_KERNEL_CONVERGENCE_INVENTORY.md. Add a real owner-boundary "
                        + "reason before extending the allowlist:\n"
                        + String.join("\n", violations));
    }

    @Test
    void sdkMainDoesNotUseAggregateTaskUpdateCommand() throws IOException {
        List<String> violations = Files.walk(Path.of("src/main/java"))
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> contains(path, ".updateTask("))
                .map(path -> path + " calls aggregate updateTask; use an intent-shaped task command")
                .toList();

        assertTrue(violations.isEmpty(),
                "SDK main code must not mutate a Task aggregate and call updateTask(Task). "
                        + "Use TaskCommandService.patchTaskDefinition or another intent-shaped command:\n"
                        + String.join("\n", violations));
    }

    private static boolean contains(Path path, String token) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).contains(token);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }

    private static List<String> engineImports(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(line -> line.startsWith("import com.xa.mass.engine."))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }
}
