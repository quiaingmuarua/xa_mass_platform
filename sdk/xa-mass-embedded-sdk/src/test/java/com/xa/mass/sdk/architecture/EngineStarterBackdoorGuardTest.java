package com.xa.mass.sdk.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
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

        assertTrue(Pattern.compile("\\bpublic\\s+TaskReadOperations\\s+taskReads\\s*\\(")
                        .matcher(massApplication)
                        .find(),
                "MassApplication must expose task reads through the unified TaskReadOperations entry");
        assertTrue(Pattern.compile("\\bpublic\\s+TaskReadOperations\\s+getTaskReadOperations\\s*\\(")
                        .matcher(engineConfig)
                        .find(),
                "EngineConfig must expose one unified task read entry for starter assembly");
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
                "MassSdkApplication task reads must route through TaskReadOperations, not MassApplication raw reads");
    }

    @Test
    void unifiedTaskReadSurfaceLivesInSdkApiAndStarterImplementationOnly() {
        Path repo = EngineCallerSurfaceGuardSupport.repositoryRoot();
        Path apiSurface = repo.resolve(
                "sdk/xa-mass-embedded-sdk-api/src/main/java/com/xa/mass/sdk/TaskReadOperations.java");
        Path oldEmbeddedSurface = repo.resolve(
                "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/TaskReadOperations.java");
        Path oldStarterImplementation = repo.resolve(
                "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/StarterTaskReadOperations.java");
        String engineReadImplementation = EngineCallerSurfaceGuardSupport.read(
                "xa-mass-engine-starter/src/main/java/com/xa/mass/starter/config/EngineTaskReadOperations.java");

        assertTrue(Files.isRegularFile(apiSurface),
                "TaskReadOperations must be an SDK API contract, not an embedded-sdk implementation-local interface");
        assertFalse(Files.exists(oldEmbeddedSurface),
                "embedded-sdk main must not keep its own TaskReadOperations interface");
        assertFalse(Files.exists(oldStarterImplementation),
                "StarterTaskReadOperations must stay deleted; engine-starter owns the read implementation");
        assertTrue(engineReadImplementation.contains("final class EngineTaskReadOperations implements TaskReadOperations"),
                "EngineTaskReadOperations must remain the package-private engine-starter implementation");
        assertFalse(engineReadImplementation.contains("public final class EngineTaskReadOperations"),
                "EngineTaskReadOperations must not become a public starter-facing surface");
    }
}
