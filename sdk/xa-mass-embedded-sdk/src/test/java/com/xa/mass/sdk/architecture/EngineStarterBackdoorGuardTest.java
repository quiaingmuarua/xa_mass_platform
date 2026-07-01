package com.xa.mass.sdk.architecture;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;

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
    void sdkTaskDiagnosticsDoesNotExposeLegacyRuntimeDtos() {
        String source = EngineCallerSurfaceGuardSupport.read(
                "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/TaskDiagnosticOperations.java");
        String defaultOperations = EngineCallerSurfaceGuardSupport.read(
                "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/DefaultTaskDiagnosticOperations.java");
        String massApplication = EngineCallerSurfaceGuardSupport.read(
                "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/MassApplication.java");
        String massEngine = EngineCallerSurfaceGuardSupport.read(
                "xa-mass-engine-starter/src/main/java/com/xa/mass/starter/MassEngine.java");
        String engineConfig = EngineCallerSurfaceGuardSupport.read(
                "xa-mass-engine-starter/src/main/java/com/xa/mass/starter/config/EngineConfig.java");
        Pattern legacyDiagnosticDto = Pattern.compile("\\b(?:TaskWorkStats|ActiveLeaseRecord)\\b");

        assertFalse(source.contains("com.xa.mass.runtime.api"),
                "TaskDiagnosticOperations must expose SDK-owned snapshots, not mass-runtime-api DTOs");
        assertFalse(Pattern.compile("\\bTaskWorkStats\\s+getTaskWorkStats\\s*\\(").matcher(source).find(),
                "TaskDiagnosticOperations must not return legacy TaskWorkStats");
        assertFalse(Pattern.compile("\\bList\\s*<\\s*ActiveLeaseRecord\\s*>\\s+getActiveLeases\\s*\\(")
                        .matcher(source)
                        .find(),
                "TaskDiagnosticOperations must not return legacy ActiveLeaseRecord");
        assertFalse(legacyDiagnosticDto.matcher(defaultOperations).find(),
                "DefaultTaskDiagnosticOperations must consume SDK-owned diagnostic snapshots, not legacy runtime DTOs");
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
}
