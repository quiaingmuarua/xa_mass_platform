package com.xa.mass.server.support;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerMainSourceArchitectureGuardTest {

    private static final Path SERVER_MAIN_SOURCE_ROOT = Path.of("src/main/java");

    private static final Map<String, String> FORBIDDEN_IMPORT_FRAGMENTS = Map.of(
            "base", "import com.xa.mass.base.",
            "engine", "import com.xa.mass.engine.",
            "sdk.internal", "import com.xa.mass.sdk.internal."
    );

    @Test
    void serverMainSourceDoesNotImportBaseEngineOrSdkInternal() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(SERVER_MAIN_SOURCE_ROOT)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> collectViolations(path, violations));
        }

        assertTrue(violations.isEmpty(),
                "server main source must stay on sdk-owned surfaces only:\n" + String.join("\n", violations));
    }

    @Test
    void taskResultEndpointsDoNotUseProjectionRowsAsResultSource() throws IOException {
        Path controller = SERVER_MAIN_SOURCE_ROOT.resolve("com/xa/mass/api/internal/TaskApiController.java");
        String source = Files.readString(controller, StandardCharsets.UTF_8);

        assertTrue(!source.contains("TaskMessageProjection"),
                "TaskApiController result endpoints must use TaskResultQueryOperations, not TaskMessageProjection");
        assertTrue(!source.contains("getTaskMessageProjections"),
                "TaskApiController must not read TaskDetailStore projection rows for public results");
    }

    @Test
    void serverProductionDoesNotUseSharedProjectionInfrastructure() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(SERVER_MAIN_SOURCE_ROOT)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            String source = Files.readString(path, StandardCharsets.UTF_8);
                            if (source.contains("TaskDetailStore")
                                    || source.contains("TaskDetailStoreTaskReviewReadModel")
                                    || source.contains("TaskDetailStoreReviewMaterializer")
                                    || source.contains("TaskMessageProjection")
                                    || source.contains("TaskMessageAttemptProjection")
                                    || source.contains("com.xa.mass.storage.api.projection")) {
                                violations.add(path + " uses retired shared projection infrastructure");
                            }
                        } catch (IOException e) {
                            violations.add(path + " could not be read: " + e.getMessage());
                        }
                    });
        }

        assertTrue(violations.isEmpty(),
                "server production review/export must use server-local TaskReviewStore materialization, "
                        + "not retired shared projection infrastructure:\n"
                        + String.join("\n", violations));
    }

    @Test
    void externalWorkerControllerDoesNotReintroduceWorkerContextCompatibilitySurface() throws IOException {
        Path controller = SERVER_MAIN_SOURCE_ROOT.resolve("com/xa/mass/api/internal/ExternalWorkerApiController.java");
        String source = Files.readString(controller, StandardCharsets.UTF_8);

        assertTrue(!source.contains("ExternalWorkerOperations"),
                "ExternalWorkerApiController must inject worker registry/client surfaces directly");
        assertTrue(!source.contains("WorkerContextCompatibilityOperations"),
                "ExternalWorkerApiController must not reintroduce WorkerContext compatibility routes");
    }

    @Test
    void catalogCapabilityViewsDoNotUseWorkerRowCapabilityFallback() throws IOException {
        Path controller = SERVER_MAIN_SOURCE_ROOT.resolve("com/xa/mass/api/internal/CatalogController.java");
        String source = Files.readString(controller, StandardCharsets.UTF_8);

        assertTrue(!source.contains(".getSupportedProjects()"),
                "CatalogController must derive project capability from WorkerGroup views, not worker-row hints");
        assertTrue(!source.contains(".getSupportedEventCodes()"),
                "CatalogController must derive event capability from WorkerGroup views, not worker-row hints");
    }

    @Test
    void controlConsoleScenarioDoesNotSeedTasksOrWorkersFromServerMainSource() throws IOException {
        Path provider = SERVER_MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/server/bootstrap/ControlConsoleScenarioBootstrapDataProvider.java");
        Path configuration = SERVER_MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/server/ControlConsoleScenarioBootstrapConfiguration.java");
        String configurationSource = Files.readString(configuration, StandardCharsets.UTF_8);

        assertTrue(!Files.exists(provider),
                "control-console task/worker scenario data must live outside server main source");
        assertTrue(!configurationSource.contains("loadInto("),
                "control-console server bootstrap must not call scenario data loadInto from startup");
        assertTrue(!configurationSource.contains("MassBootstrapDataProvider"),
                "control-console server bootstrap must not register a scenario MassBootstrapDataProvider");
    }

    @Test
    void kernelAndTransportDoNotImportServerIamStores() throws IOException {
        Path repoRoot = Path.of("..").toAbsolutePath().normalize();
        List<Path> scannedRoots = List.of(
                repoRoot.resolve("xa-mass-engine/src/main/java"),
                repoRoot.resolve("transport"),
                repoRoot.resolve("platform_infra")
        );
        List<String> violations = new ArrayList<>();
        for (Path root : scannedRoots) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> {
                            try {
                                String source = Files.readString(path, StandardCharsets.UTF_8);
                                if (source.contains("import com.xa.mass.api.auth.")) {
                                    violations.add(repoRoot.relativize(path) + " imports server IAM/auth store package");
                                }
                            } catch (IOException e) {
                                violations.add(path + " could not be read: " + e.getMessage());
                            }
                        });
            }
        }

        assertTrue(violations.isEmpty(),
                "IAM/auth stores must stay in server control-plane, not kernel/runtime/transport:\n"
                        + String.join("\n", violations));
    }

    @Test
    void reviewMaterializationQueueStaysOutOfSharedInfraAndEngine() throws IOException {
        Path repoRoot = Path.of("..").toAbsolutePath().normalize();
        List<Path> scannedRoots = List.of(
                repoRoot.resolve("platform_infra"),
                repoRoot.resolve("xa-mass-engine/src/main/java"),
                repoRoot.resolve("xa-mass-worker-runtime/src/main/java"),
                repoRoot.resolve("transport")
        );
        List<String> violations = new ArrayList<>();
        for (Path root : scannedRoots) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> {
                            try {
                                String source = Files.readString(path, StandardCharsets.UTF_8);
                                if (source.contains("import com.xa.mass.api.review.TaskReviewReport")
                                        || source.contains("import com.xa.mass.api.review.TaskReviewMaterializer")
                                        || source.contains("import com.xa.mass.api.review.TaskReviewMaterialization")
                                        || source.contains("import com.xa.mass.api.review.TaskReviewStore")
                                        || source.contains("import com.xa.mass.api.review.QueueBackedTaskReview")) {
                                    violations.add(repoRoot.relativize(path)
                                            + " imports server review materialization/store contract");
                                }
                            } catch (IOException e) {
                                violations.add(path + " could not be read: " + e.getMessage());
                            }
                        });
            }
        }

        assertTrue(violations.isEmpty(),
                "review materialization queue/materializer/store contracts must stay server-owned:\n"
                        + String.join("\n", violations));
    }

    @Test
    void productionReviewWritesUseQueueBackedWriter() throws IOException {
        Path application = SERVER_MAIN_SOURCE_ROOT.resolve("com/xa/mass/server/XaMassServerApplication.java");
        String source = Files.readString(application, StandardCharsets.UTF_8);

        assertTrue(source.contains("new QueueBackedTaskReviewReadModelWriter(taskReviewReportQueue, policy)"),
                "server production review writer bean must submit through the review report queue and server policy");
        assertTrue(source.contains("new TaskReviewStoreMaterializer(taskReviewStore)"),
                "server production review materializer must write through server-local review store backing");
        assertTrue(!source.contains("taskDetailStore("),
                "server production review wiring must not request TaskDetailStore from shared infra");
    }

    @Test
    void serverProfileDefaultDoesNotOverrideExplicitSpringProfiles() throws IOException {
        Path application = SERVER_MAIN_SOURCE_ROOT.resolve("com/xa/mass/server/XaMassServerApplication.java");
        String source = Files.readString(application, StandardCharsets.UTF_8);
        Path applicationYml = Path.of("src/main/resources/application.yml");
        String applicationConfig = Files.readString(applicationYml, StandardCharsets.UTF_8);

        assertTrue(applicationConfig.contains("default: dev"),
                "application.yml must use Spring's default profile support for no-arg dev startup");
        assertTrue(!applicationConfig.contains("active: local"),
                "application.yml must not activate the old local profile");
        assertTrue(!source.contains("System.setProperty(\"spring.profiles.active\""),
                "server main must not overwrite explicit profiles from env, args, or Spring config");
    }

    @Test
    void serverRunnableBeansAreAvailableForDevAndProd() throws IOException {
        Path application = SERVER_MAIN_SOURCE_ROOT.resolve("com/xa/mass/server/XaMassServerApplication.java");
        String source = Files.readString(application, StandardCharsets.UTF_8);

        assertTrue(source.contains("@Profile({\"dev\", \"prod\"})"),
                "server runtime beans must be selectable through both dev and prod profiles");
        assertTrue(!source.contains("@Profile(\"dev\")\n    public JdbcStorageRuntime"),
                "server JDBC storage runtime must not be dev-only");
        assertTrue(!source.contains("@Profile(\"dev\")\n    public MassSdkApplication"),
                "embedded SDK runtime application must not be dev-only");
    }

    @Test
    void prodProfileKeepsStorageRuntimeAndTraceLayersSeparate() throws IOException {
        String prodConfig = Files.readString(Path.of("src/main/resources/application-prod.yml"), StandardCharsets.UTF_8);

        assertTrue(prodConfig.contains("mode: jdbc-sqlite"),
                "prod profile must select SQLite control-plane storage");
        assertTrue(prodConfig.contains("mode: redis"),
                "prod profile must select Redis runtime");
        assertTrue(prodConfig.contains("store: redis"),
                "prod profile must select Redis transport delivery/presence stores");
        assertTrue(!prodConfig.contains("trace"),
                "prod profile convergence must not add trace DB ingestion settings");
    }

    @Test
    void reviewQueueApiDoesNotGrowRuntimeDecisionVocabulary() throws IOException {
        Path reviewRoot = SERVER_MAIN_SOURCE_ROOT.resolve("com/xa/mass/api/review");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(reviewRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        return fileName.contains("ReportQueue")
                                || fileName.contains("ReportEvent")
                                || fileName.contains("Materializer");
                    })
                    .forEach(path -> {
                        try {
                            String source = Files.readString(path, StandardCharsets.UTF_8);
                            if (source.contains("dispatch") || source.contains("Dispatch")
                                    || source.contains("lease") || source.contains("Lease")
                                    || source.contains("scheduling") || source.contains("Scheduling")
                                    || source.contains("TerminalPolicy")) {
                                violations.add(path + " exposes runtime decision vocabulary");
                            }
                        } catch (IOException e) {
                            violations.add(path + " could not be read: " + e.getMessage());
                        }
                    });
        }

        assertTrue(violations.isEmpty(),
                "review queue/materializer APIs must not become scheduling or lifecycle decision surfaces:\n"
                        + String.join("\n", violations));
    }

    private static void collectViolations(Path path, List<String> violations) {
        String source;
        try {
            source = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            violations.add(path + " could not be read: " + e.getMessage());
            return;
        }

        FORBIDDEN_IMPORT_FRAGMENTS.forEach((label, fragment) -> {
            if (source.contains(fragment)) {
                violations.add(path + " imports forbidden " + label + " type");
            }
        });
    }
}
