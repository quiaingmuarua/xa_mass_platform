package com.xa.mass.sdk.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedSdkEngineDependencyGuardTest {

    private static final Pattern DIRECT_ENGINE_DEPENDENCY = Pattern.compile(
            "<artifactId>\\s*xa-mass-engine\\s*</artifactId>");

    private static final Pattern FORBIDDEN_ENGINE_IMPORT = Pattern.compile(
            "\\bimport\\s+com\\.xa\\.mass\\.engine\\.(?:"
                    + "TaskManager|TaskCommandPort|TaskQueryPort|TaskCommandService|TaskQueryService|TaskEventService|"
                    + "TaskResultService|TaskManagerResultIngestFacade|EngineRuntimeKernel|"
                    + "WorkerControlRuntime|TaskStageEvidenceService|TaskAssignmentRuntimePort|"
                    + "TaskDispatchWakeupPort|TaskLeaseMaintenancePort|TaskShellLifecycleMaintenancePort|"
                    + "TaskRuntimeRecoveryPort"
                    + ")(?:\\.|;)");

    @Test
    void embeddedSdkDoesNotDependDirectlyOnEngineModule() {
        String pom = EngineCallerSurfaceGuardSupport.read("sdk/xa-mass-embedded-sdk/pom.xml");

        assertFalse(DIRECT_ENGINE_DEPENDENCY.matcher(pom).find(),
                "embedded-sdk must depend on xa-mass-engine-starter, not xa-mass-engine directly");
    }

    @Test
    void embeddedSdkMainSourcesDoNotImportEngineServicesOrRuntimeOwners() {
        List<String> violations = new ArrayList<>();
        for (Path sourcePath : EngineCallerSurfaceGuardSupport.javaSourceFiles(
                "sdk/xa-mass-embedded-sdk/src/main/java")) {
            String source = EngineCallerSurfaceGuardSupport.read(sourcePath);
            if (FORBIDDEN_ENGINE_IMPORT.matcher(source).find()) {
                violations.add(sourcePath.toString());
            }
        }

        assertTrue(violations.isEmpty(),
                "embedded-sdk main sources may keep only inventoried value-contract exceptions; "
                        + "engine services/runtime owners must stay behind engine-starter:\n"
                        + String.join("\n", violations));
    }

    @Test
    void embeddedSdkMainSourcesDoNotUseEngineBackdoorGetters() {
        List<String> violations = new ArrayList<>();
        for (Path sourcePath : EngineCallerSurfaceGuardSupport.javaSourceFiles(
                "sdk/xa-mass-embedded-sdk/src/main/java")) {
            String source = EngineCallerSurfaceGuardSupport.read(sourcePath);
            if (source.contains("getEngine(") || source.contains("getConfig(")
                    || source.contains("getEngine().getConfig()")) {
                violations.add(sourcePath.toString());
            }
        }

        assertTrue(violations.isEmpty(),
                "embedded-sdk main sources must not reach engine through getEngine()/getConfig():\n"
                        + String.join("\n", violations));
    }
}
