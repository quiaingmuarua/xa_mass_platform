package com.xa.mass.sdk.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineStarterBackdoorGuardTest {

    @Test
    void massApplicationDoesNotExposeRawMassEngine() {
        String source = EngineCallerSurfaceGuardSupport.read(
                "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/MassApplication.java");

        assertFalse(Pattern.compile("\\bpublic\\s+MassEngine\\s+getEngine\\s*\\(").matcher(source).find(),
                "MassApplication.getEngine() is a deleted ECSP backdoor");
    }

    @Test
    void massEngineDoesNotExposeRawEngineConfig() {
        String source = EngineCallerSurfaceGuardSupport.read(
                "xa-mass-engine-starter/src/main/java/com/xa/mass/starter/MassEngine.java");

        assertFalse(Pattern.compile("\\bpublic\\s+EngineConfig\\s+getConfig\\s*\\(").matcher(source).find(),
                "MassEngine.getConfig() is a deleted ECSP backdoor");
    }

    @Test
    void sourceDoesNotKeepChainedBackdoorCalls() {
        String embeddedStarter = EngineCallerSurfaceGuardSupport.read(
                "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/MassApplication.java");
        String sdkFacade = EngineCallerSurfaceGuardSupport.read(
                "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/MassSdkApplication.java");

        assertFalse(embeddedStarter.contains("getEngine().getConfig()"));
        assertFalse(sdkFacade.contains("getEngine().getConfig()"));
    }

    @Test
    void engineConfigDoesNotExposeOldEngineTaskCommandPortAsPublicStarterSurface() {
        String source = EngineCallerSurfaceGuardSupport.read(
                "xa-mass-engine-starter/src/main/java/com/xa/mass/starter/config/EngineConfig.java");
        int internalKernelConfigStart = source.indexOf("private final class KernelConfigView");
        String starterFacingSource = internalKernelConfigStart >= 0 ? source.substring(0, internalKernelConfigStart) : source;

        assertFalse(Pattern.compile("\\bpublic\\s+TaskCommandPort\\s+getTaskCommandPort\\s*\\(")
                        .matcher(starterFacingSource)
                        .find(),
                "EngineConfig must not expose old engine TaskCommandPort as a starter-facing lifecycle command path");
    }

    @Test
    void nonAssemblyCodeDoesNotConsumeRawTaskRuntimePortSet() {
        List<String> checkedRoots = List.of(
                "sdk/xa-mass-embedded-sdk/src/main/java",
                "xa-mass-server/src/main/java",
                "xa-mass-testing/src/main/java"
        );
        List<String> forbidden = List.of(
                "com.xa.mass.task.runtime.starter.TaskRuntimeHandle",
                "com.xa.mass.task.runtime.starter.TaskRuntimePortSet"
        );
        List<String> violations = new ArrayList<>();

        for (String root : checkedRoots) {
            for (Path path : EngineCallerSurfaceGuardSupport.javaSourceFiles(root)) {
                String source = EngineCallerSurfaceGuardSupport.read(path);
                for (String token : forbidden) {
                    if (source.contains(token)) {
                        violations.add(path + " imports raw task-runtime assembly surface: " + token);
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Only task-runtime starter internals and engine-starter assembly may consume raw TaskRuntimeHandle/"
                        + "TaskRuntimePortSet; external code must use TaskRuntimeCommandPort or TaskReadViewPort:\n"
                        + String.join("\n", violations));
    }

    @Test
    void starterAndEmbeddedSdkDoNotExposeLegacyTaskRuntimeInjection() {
        String engineConfig = EngineCallerSurfaceGuardSupport.read(
                "xa-mass-engine-starter/src/main/java/com/xa/mass/starter/config/EngineConfig.java");
        String engineBuilder = EngineCallerSurfaceGuardSupport.read(
                "xa-mass-engine-starter/src/main/java/com/xa/mass/starter/builder/MassEngineBuilder.java");
        String applicationBuilder = EngineCallerSurfaceGuardSupport.read(
                "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/builder/MassApplicationBuilder.java");
        String massSdk = EngineCallerSurfaceGuardSupport.read(
                "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/MassSdk.java");

        Pattern publicLegacyRuntimeAccessor = Pattern.compile(
                "\\bpublic\\s+[^\\n{;]+\\s+(?:get|set)Task(?:Work|Result)Runtime\\s*\\(");
        Pattern legacyRuntimeBuilderMethod = Pattern.compile(
                "\\bpublic\\s+[^\\n{;]+\\s+task(?:Work|Result)Runtime\\s*\\(");

        assertFalse(publicLegacyRuntimeAccessor.matcher(engineConfig).find(),
                "EngineConfig must not expose legacy TaskWorkRuntime/TaskResultRuntime getter or setter");
        assertFalse(engineConfig.contains("legacyTaskWorkRuntimeForUnmigratedPath"),
                "EngineConfig must not expose package-private legacy TaskWorkRuntime fallback handles");
        assertFalse(engineConfig.contains("legacyTaskResultRuntimeForUnmigratedPath"),
                "EngineConfig must not expose package-private legacy TaskResultRuntime fallback handles");
        assertFalse(engineConfig.contains("new InMemoryTaskWorkRuntime"),
                "EngineConfig must not keep a runnable in-memory legacy TaskWorkRuntime fallback");
        assertFalse(engineConfig.contains("new InMemoryTaskResultRuntime"),
                "EngineConfig must not keep a runnable in-memory legacy TaskResultRuntime fallback");
        assertFalse(legacyRuntimeBuilderMethod.matcher(engineBuilder).find(),
                "MassEngineBuilder must not expose legacy taskWorkRuntime/taskResultRuntime injection");
        assertFalse(legacyRuntimeBuilderMethod.matcher(applicationBuilder).find(),
                "MassApplicationBuilder.EngineBuilder must not expose legacy taskWorkRuntime/taskResultRuntime injection");
        assertFalse(legacyRuntimeBuilderMethod.matcher(massSdk).find(),
                "MassSdk.EngineOptions must not expose legacy taskWorkRuntime/taskResultRuntime injection");
    }

    @Test
    void sdkTaskReadsDoNotExposeLegacyRuntimeDtos() {
        String source = EngineCallerSurfaceGuardSupport.read(
                "sdk/xa-mass-embedded-sdk-api/src/main/java/com/xa/mass/sdk/TaskReadOperations.java");
        String massApplication = EngineCallerSurfaceGuardSupport.read(
                "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/MassApplication.java");
        String massEngine = EngineCallerSurfaceGuardSupport.read(
                "xa-mass-engine-starter/src/main/java/com/xa/mass/starter/MassEngine.java");
        String engineConfig = EngineCallerSurfaceGuardSupport.read(
                "xa-mass-engine-starter/src/main/java/com/xa/mass/starter/config/EngineConfig.java");
        Pattern legacyDiagnosticDto = Pattern.compile("\\b(?:TaskWorkStats|ActiveLeaseRecord)\\b");

        assertFalse(source.contains("com.xa.mass.runtime.api"),
                "TaskReadOperations must expose SDK-owned snapshots, not mass-runtime-api DTOs");
        assertFalse(Pattern.compile("\\bTaskWorkStats\\s+getTaskWorkStats\\s*\\(").matcher(source).find(),
                "TaskReadOperations must not return legacy TaskWorkStats");
        assertFalse(Pattern.compile("\\bList\\s*<\\s*ActiveLeaseRecord\\s*>\\s+getActiveLeases\\s*\\(")
                        .matcher(source)
                        .find(),
                "TaskReadOperations must not return legacy ActiveLeaseRecord");
        assertFalse(legacyDiagnosticDto.matcher(massApplication).find(),
                "MassApplication must expose SDK-owned diagnostic snapshots across the embedded SDK boundary");
        assertFalse(legacyDiagnosticDto.matcher(massEngine).find(),
                "MassEngine must expose SDK-owned diagnostic snapshots across the engine-starter boundary");
        assertFalse(legacyDiagnosticDto.matcher(engineConfig).find(),
                "EngineConfig must expose SDK-owned diagnostic snapshots across starter-facing diagnostics");
    }

    @Test
    void starterAndEmbeddedSdkResultReadsDoNotExposeLegacyRuntimeDtos() {
        String sdkFacade = EngineCallerSurfaceGuardSupport.read(
                "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/MassSdkApplication.java");
        String massApplication = EngineCallerSurfaceGuardSupport.read(
                "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/MassApplication.java");
        String massEngine = EngineCallerSurfaceGuardSupport.read(
                "xa-mass-engine-starter/src/main/java/com/xa/mass/starter/MassEngine.java");
        String engineConfig = EngineCallerSurfaceGuardSupport.read(
                "xa-mass-engine-starter/src/main/java/com/xa/mass/starter/config/EngineConfig.java");

        Pattern legacyResultDto = Pattern.compile("\\b(?:TaskResultRuntimeRow|TaskResultWindow)\\b");

        assertFalse(legacyResultDto.matcher(sdkFacade).find(),
                "MassSdkApplication must consume SDK-owned result snapshots, not legacy runtime result DTOs");
        assertFalse(legacyResultDto.matcher(massApplication).find(),
                "MassApplication must expose SDK-owned result snapshots across the embedded SDK boundary");
        assertFalse(legacyResultDto.matcher(massEngine).find(),
                "MassEngine must expose SDK-owned result snapshots across the engine-starter boundary");
        assertFalse(legacyResultDto.matcher(engineConfig).find(),
                "EngineConfig must expose SDK-owned result snapshots across starter-facing result reads");
    }

    @Test
    void massApplicationExposesOnlyUnifiedTaskReadSurface() {
        String massApplication = EngineCallerSurfaceGuardSupport.read(
                "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/MassApplication.java");
        String massEngine = EngineCallerSurfaceGuardSupport.read(
                "xa-mass-engine-starter/src/main/java/com/xa/mass/starter/MassEngine.java");
        String sdkFacade = EngineCallerSurfaceGuardSupport.read(
                "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/MassSdkApplication.java");
        String engineConfig = EngineCallerSurfaceGuardSupport.read(
                "xa-mass-engine-starter/src/main/java/com/xa/mass/starter/config/EngineConfig.java");
        String taskReadViewPort = EngineCallerSurfaceGuardSupport.read(
                "sdk/xa-mass-task-runtime-starter-sdk/src/main/java/com/xa/mass/task/runtime/starter/TaskReadViewPort.java");

        assertTrue(Pattern.compile("\\bpublic\\s+TaskReadViewPort\\s+taskReadView\\s*\\(")
                        .matcher(massApplication)
                        .find(),
                "MassApplication must expose task reads through the approved TaskReadViewPort entry");
        assertTrue(Pattern.compile("\\bpublic\\s+TaskReadViewPort\\s+getTaskReadViewPort\\s*\\(")
                        .matcher(engineConfig)
                        .find(),
                "EngineConfig must expose one approved task read-view entry for starter assembly");
        assertFalse(Pattern.compile("\\bpublic\\s+TaskReadOperations\\s+taskReads\\s*\\(")
                        .matcher(massApplication)
                        .find(),
                "MassApplication must not keep TaskReadOperations as a starter read backdoor");
        assertFalse(Pattern.compile("\\bpublic\\s+TaskReadOperations\\s+getTaskReadOperations\\s*\\(")
                        .matcher(engineConfig)
                        .find(),
                "EngineConfig must not keep TaskReadOperations as a starter read backdoor");
        assertTrue(taskReadViewPort.contains("extends TaskReadOperations"),
                "TaskReadViewPort must be the approved read-view surface while reusing current SDK snapshots");
        assertFalse(Pattern.compile("\\bpublic\\s+TaskShellStore\\s+getTaskShellStore\\s*\\(")
                        .matcher(engineConfig)
                        .find(),
                "EngineConfig must not expose raw task shell storage as a starter-facing read surface");
        for (String forbiddenPublicRead : java.util.List.of(
                "Task getTask",
                "List<Task> listTasksPaged",
                "List<Task> getTasksByStatus",
                "TaskResultWindowSnapshot readTaskResults",
                "TaskWorkStatsSnapshot getTaskWorkStats",
                "List<TaskActiveLeaseSnapshot> getActiveLeases",
                "Optional<TaskWorkFinalSnapshot> getVisibleTaskResultByMessageId",
                "long countVisibleTaskResults",
                "TaskStateValidationResult validateTaskState",
                "TaskStateResolutionResult resolveTaskState",
                "TaskStateValidationSnapshot validateTaskState",
                "TaskStateResolutionSnapshot resolveTaskState")) {
            Pattern publicRawRead = Pattern.compile("\\bpublic\\s+" + Pattern.quote(forbiddenPublicRead) + "\\s*\\(");
            assertFalse(publicRawRead.matcher(massApplication).find(),
                    "MassApplication must not re-expose raw task read method: " + forbiddenPublicRead);
            assertFalse(publicRawRead.matcher(massEngine).find(),
                    "MassEngine must not re-expose raw task read method: " + forbiddenPublicRead);
            assertFalse(publicRawRead.matcher(engineConfig).find(),
                    "EngineConfig must not re-expose raw task read method: " + forbiddenPublicRead);
        }

        Pattern rawDelegateRead = Pattern.compile(
                "\\bdelegate\\.(?:getTask|listTasksPaged|getTasksByStatus|readTaskResults|"
                        + "getVisibleTaskResultByMessageId|countVisibleTaskResults|"
                        + "validateTaskState|resolveTaskState|getTaskWorkStats|getActiveLeases)\\s*\\(");
        assertFalse(rawDelegateRead.matcher(sdkFacade).find(),
                "MassSdkApplication task reads must route through TaskReadViewPort, not MassApplication raw reads");
        assertTrue(sdkFacade.contains("delegate.taskReadView()"),
                "MassSdkApplication must consume the approved TaskReadViewPort under its TaskReadOperations facade");
    }

    @Test
    void taskReadSurfaceDoesNotKeepDeletedEmbeddedImplementationAliases() {
        Path repo = EngineCallerSurfaceGuardSupport.repositoryRoot();
        Path oldEmbeddedSurface = repo.resolve(
                "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/TaskReadOperations.java");
        Path oldStarterImplementation = repo.resolve(
                "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/StarterTaskReadOperations.java");

        assertFalse(Files.exists(oldEmbeddedSurface),
                "embedded-sdk main must not keep its own TaskReadOperations interface");
        assertFalse(Files.exists(oldStarterImplementation),
                "StarterTaskReadOperations must stay deleted; engine-starter owns the read implementation");
    }

    @Test
    void taskReadProviderDoesNotUseTaskShellStoreAsReadOwner() {
        String engineReadProvider = EngineCallerSurfaceGuardSupport.read(
                "xa-mass-engine-starter/src/main/java/com/xa/mass/starter/config/EngineTaskReadOperations.java");

        assertFalse(engineReadProvider.contains("TaskShellStore"),
                "task read provider must not import or name TaskShellStore as its read owner");
        assertFalse(engineReadProvider.contains("TaskManager"),
                "task read provider must not route TaskReadViewPort through TaskManager");
        assertFalse(engineReadProvider.contains("TaskQueryPort"),
                "task read provider must not route TaskReadViewPort through TaskQueryPort");
        assertFalse(engineReadProvider.contains("getTaskShellStore"),
                "task read provider must not reach EngineConfig task shell storage for reads");
        assertFalse(engineReadProvider.contains("taskQueryPort"),
                "task read provider must not reach EngineConfig task query port for reads");
        assertFalse(engineReadProvider.contains("ensureTaskManager"),
                "task read provider must not construct or reach TaskManager for reads");
        assertFalse(Pattern.compile("\\.\\s*(?:getTask|listTasksPaged|getTasksByStatus|getTasksByProject)\\s*\\(")
                        .matcher(engineReadProvider)
                        .find(),
                "task read provider must not satisfy reads through TaskShellStore query methods");
    }
}
