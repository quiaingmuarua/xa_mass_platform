package com.xa.mass.client;

import com.xa.mass.client.worker.session.WorkerSession;
import com.xa.mass.client.worker.session.WorkerSessionSpec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaExternalSdkArchitectureGuardTest {
    private static final Path MAIN_SOURCE = Path.of("src/main/java");
    private static final Path WORKER_HANDLER_SOURCE =
            Path.of("src/main/java/com/xa/mass/client/worker/handler");
    private static final List<String> PUBLIC_PLATFORM_ROUTE_PREFIXES = List.of(
            "\"/api/v1",
            "\"/worker-api/v1"
    );
    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "import com.xa.mass.engine.",
            "import com.xa.mass.starter.",
            "import com.xa.mass.worker.runtime.",
            "import com.xa.mass.command.",
            "import com.xa.mass.kernel.spi.",
            "import com.xa.mass.base.",
            "import com.xa.mass.api.",
            "import com.xa.mass.transport.",
            "import com.xa.mass.transport.runtime.",
            "import com.xa.mass.transport.websocket.",
            "import com.xa.mass.transport.socket.",
            "import com.xa.mass.transport.polling.",
            "import com.xa.mass.sdk.auth.",
            "import com.xa.mass.sdk.authz.",
            "import com.xa.mass.sdk.catalog.",
            "import com.xa.mass.sdk.Task",
            "import com.xa.mass.sdk.Worker"
    );
    private static final List<String> FORBIDDEN_ARTIFACT_IDS = List.of(
            "xa-mass-embedded-sdk",
            "xa-mass-embedded-sdk-api",
            "xa-mass-base",
            "xa-mass-kernel-spi",
            "xa-mass-worker-pack",
            "xa-mass-engine",
            "xa-mass-server",
            "xa-mass-worker-runtime",
            "xa-mass-transport-api",
            "xa-mass-transport-websocket",
            "xa-mass-transport-socket",
            "xa-mass-transport-polling",
            "xa-mass-transport-runtime"
    );

    @Test
    void productionCodeDoesNotImportRuntimeServerOrEmbeddedSdkOwners() throws IOException {
        try (var paths = Files.walk(MAIN_SOURCE)) {
            List<Path> javaFiles = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
            for (Path javaFile : javaFiles) {
                String source = Files.readString(javaFile);
                for (String forbiddenImport : FORBIDDEN_IMPORTS) {
                    assertFalse(source.contains(forbiddenImport),
                            javaFile + " must not contain " + forbiddenImport);
                }
            }
        }
    }

    @Test
    void pomDoesNotDependOnRuntimeServerEmbeddedSdkOrBaseArtifacts() throws IOException {
        for (Path pomPath : List.of(Path.of("pom.xml"), Path.of("pom.consumer.xml"))) {
            String pom = Files.readString(pomPath);
            for (String forbiddenArtifactId : FORBIDDEN_ARTIFACT_IDS) {
                String token = "<artifactId>" + forbiddenArtifactId + "</artifactId>";
                assertFalse(pom.contains(token), pomPath + " must not contain " + token);
            }
        }
    }

    @Test
    void workerSessionContractStaysNarrow() {
        Set<String> methodNames = Arrays.stream(WorkerSession.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("workerId", "workerGroupId", "transportHint", "start", "isRunning", "close"),
                methodNames);
    }

    @Test
    void workerSessionSpecContainsOnlySharedSessionFacts() {
        Set<String> fieldNames = Arrays.stream(WorkerSessionSpec.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("workerId", "workerGroupId", "attributes", "eventHandlers", "listener"),
                fieldNames);
    }

    @Test
    void workerHandlerRuntimeDoesNotDependOnSessionOrTransportPackages() throws IOException {
        try (var paths = Files.walk(WORKER_HANDLER_SOURCE)) {
            List<Path> javaFiles = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
            for (Path javaFile : javaFiles) {
                String source = Files.readString(javaFile);
                assertFalse(source.contains("import com.xa.mass.client.worker.session."),
                        javaFile + " must not import worker session types");
                assertFalse(source.contains("import com.xa.mass.transport."),
                        javaFile + " must not import transport types");
            }
        }
    }

    @Test
    void workerInvocationSurfacesExposeOnlyPayloadAndOpaqueCorrelation() throws IOException {
        assertFalse(Files.exists(Path.of(
                        "src/main/java/com/xa/mass/client/worker/handler/DispatchContext.java")),
                "DispatchContext must not remain as a compatibility alias");
        assertFalse(Files.exists(Path.of(
                        "src/main/java/com/xa/mass/client/worker/WorkerResultSubmitRequest.java")),
                "WorkerResultSubmitRequest must not remain as a compatibility alias");
        assertFalse(Files.exists(Path.of(
                        "src/main/java/com/xa/mass/client/worker/WorkerResultSubmitOutcome.java")),
                "WorkerResultSubmitOutcome must not remain as a compatibility alias");
        assertFalse(Files.exists(Path.of(
                        "src/main/java/com/xa/mass/client/worker/WorkerDispatchItem.java")),
                "WorkerDispatchItem must converge into WorkerInvocation instead of remaining as a second public dispatch DTO");
        assertFalse(Files.exists(Path.of(
                        "src/main/java/com/xa/mass/client/worker/ResultCorrelationRef.java")),
                "ResultCorrelationRef must not remain as a one-field wrapper around the public submit token");
        assertFalse(Files.exists(Path.of(
                        "src/main/java/com/xa/mass/client/worker/session/WorkerDispatchHandler.java")),
                "WorkerDispatchHandler must not remain as a second public handler callback");
        assertFalse(Files.exists(Path.of(
                        "src/main/java/com/xa/mass/client/worker/handler/WorkerEventHandlers.java")),
                "WorkerEventHandlers must not remain as a public Map wrapper");
        assertFalse(Files.exists(Path.of(
                        "src/main/java/com/xa/mass/client/worker/handler/WorkerEventInvocation.java")),
                "WorkerEventInvocation must not expose handler runtime outcome as a public model");
        assertFalse(Files.exists(Path.of(
                        "src/main/java/com/xa/mass/client/worker/handler/WorkerResultSink.java")),
                "WorkerResultSink must not expose a second public result-submit hook");

        assertNoProductionSourceContains(
                List.of(WORKER_HANDLER_SOURCE),
                "taskId",
                "messageId",
                "taskName",
                "project",
                "userId",
                "attemptId",
                "attemptNo",
                "retryCount",
                "batchId",
                "rawItem",
                "DeliveryCommand",
                "WorkerDispatchItem",
                "WorkerResultSink",
                "endpoint",
                "connectionId",
                "sessionHandle"
        );

        assertNoProductionSourceContains(
                List.of(
                        Path.of("src/main/java/com/xa/mass/client/worker/WorkerInvocation.java"),
                        Path.of("src/main/java/com/xa/mass/client/worker/WorkerResultSubmission.java")
                ),
                "taskId",
                "messageId",
                "taskName",
                "project",
                "userId",
                "attemptId",
                "attemptNo",
                "retryCount",
                "batchId",
                "DispatchContext",
                "DeliveryCommand"
        );

        assertNoProductionSourceContains(
                List.of(Path.of("src/main/java/com/xa/mass/client/worker/session")),
                "DispatchContext",
                "WorkerDispatchHandler",
                "WorkerResultSink",
                ".taskId(",
                ".messageId(",
                ".attemptId(",
                ".attemptNo(",
                ".retryCount(",
                ".batchId(",
                "DeliveryCommand"
        );
    }

    @Test
    void workerClientDoesNotExposeOldTopologyEvidenceOrCommandModels() throws IOException {
        for (String deletedFile : List.of(
                "AdapterNodeSpec.java",
                "AdapterNodeRegistrationResult.java",
                "NodeGroupBindingSpec.java",
                "NodeGroupBindingResult.java",
                "WorkerCapabilityReport.java",
                "WorkerCapabilityReportResult.java",
                "WorkerStateReport.java",
                "WorkerStateReportResult.java",
                "WorkerStateProjection.java",
                "WorkerCommand.java",
                "WorkerCommandPollRequest.java",
                "WorkerCommandPollResult.java",
                "WorkerCommandAck.java",
                "WorkerCommandAckResult.java"
        )) {
            assertFalse(Files.exists(Path.of("src/main/java/com/xa/mass/client/worker").resolve(deletedFile)),
                    deletedFile + " must not remain on the Java external worker SDK surface");
        }

        assertNoProductionSourceContains(
                List.of(Path.of("src/main/java/com/xa/mass/client/worker")),
                "registerAdapterNode",
                "bindNodeGroup",
                "reportCapability",
                "reportState",
                "pollCommands",
                "ackCommand",
                ":report-capability",
                ":report-state"
        );
    }

    @Test
    void integrationsProductionCodeDoesNotHardcodePublicPlatformRouteLiteralsOutsideSdk() throws IOException {
        Path repoRoot = resolveRepoRoot();
        Path integrationsRoot = repoRoot.resolve("integrations");
        List<String> violations = new ArrayList<>();

        try (var paths = Files.walk(integrationsRoot)) {
            List<Path> javaFiles = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !hasPathSegment(path, "target"))
                    .filter(path -> !isAllowedRouteLiteralOwner(repoRoot, path))
                    .toList();
            for (Path javaFile : javaFiles) {
                String source = Files.readString(javaFile);
                for (String routePrefix : PUBLIC_PLATFORM_ROUTE_PREFIXES) {
                    if (source.contains(routePrefix)) {
                        violations.add(repoRoot.relativize(javaFile) + " contains " + routePrefix);
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Java integrations must use MassPlatform or worker sessions for /api/v1 and /worker-api/v1 calls. "
                        + "Only SDK internals/tests and explicit roadmap exceptions may hard-code route literals: "
                        + violations);
    }

    @Test
    void externalSamplesDoNotTeachOldWorkerEvidenceRoutes() throws IOException {
        Path repoRoot = resolveRepoRoot();
        Path samplesRoot = repoRoot.resolve("integrations/samples");
        List<String> violations = new ArrayList<>();
        List<String> forbiddenTokens = List.of(
                ":report-capability",
                ":report-state",
                "availableEventCodes",
                "schedulingAttributes"
        );

        try (var paths = Files.walk(samplesRoot)) {
            List<Path> sampleFiles = paths
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        return fileName.endsWith(".md") || fileName.endsWith(".mjs");
                    })
                    .filter(path -> !hasPathSegment(path, "target"))
                    .toList();
            for (Path sampleFile : sampleFiles) {
                String source = Files.readString(sampleFile);
                for (String forbiddenToken : forbiddenTokens) {
                    if (source.contains(forbiddenToken)) {
                        violations.add(repoRoot.relativize(sampleFile) + " contains " + forbiddenToken);
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "External samples must use :report-handler-evidence / :report-runtime-evidence and evidence field names: "
                        + violations);
    }

    private static boolean isAllowedRouteLiteralOwner(Path repoRoot, Path path) {
        Path relative = repoRoot.relativize(path);
        String normalized = relative.toString().replace('\\', '/');
        return normalized.startsWith("sdk/xa-mass-java-sdk/")
                || normalized.contains("/src/test/");
    }

    private static boolean hasPathSegment(Path path, String segment) {
        for (Path part : path) {
            if (part.toString().equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static Path resolveRepoRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        for (Path cursor = current; cursor != null; cursor = cursor.getParent()) {
            if (Files.exists(cursor.resolve("pom.xml"))
                    && Files.exists(cursor.resolve("sdk/xa-mass-java-sdk/pom.xml"))) {
                return cursor;
            }
        }
        throw new IllegalStateException("Repo root not found from cwd=" + current);
    }

    private static void assertNoProductionSourceContains(List<Path> roots, String... forbiddenTokens) throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.exists(root)) {
                continue;
            }
            try (var paths = Files.walk(root)) {
                List<Path> javaFiles = paths
                        .filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> !hasPathSegment(path, "target"))
                        .toList();
                for (Path javaFile : javaFiles) {
                    String source = Files.readString(javaFile);
                    for (String forbiddenToken : forbiddenTokens) {
                        if (source.contains(forbiddenToken)) {
                            violations.add(javaFile + " contains " + forbiddenToken);
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "Forbidden Java external SDK residue: " + violations);
    }
}
