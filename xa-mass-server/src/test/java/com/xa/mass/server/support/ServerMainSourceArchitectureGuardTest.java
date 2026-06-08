package com.xa.mass.server.support;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerMainSourceArchitectureGuardTest {

    private static final Path SERVER_MAIN_SOURCE_ROOT = Path.of("src/main/java");
    private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Pattern TABLE_ROW = Pattern.compile("^\\|\\s*([^|]+?)\\s*\\|\\s*([^|]+?)\\s*\\|\\s*([^|]+?)\\s*\\|\\s*([^|]+?)\\s*\\|.*$");
    private static final Pattern CLASS_REQUEST_MAPPING = Pattern.compile("@RequestMapping\\((?:value\\s*=\\s*)?\"([^\"]*)\"\\)");
    private static final Pattern METHOD_MAPPING = Pattern.compile("@(GetMapping|PostMapping|PatchMapping|DeleteMapping)(?:\\(([^)]*)\\))?");
    private static final Pattern FIRST_QUOTED_VALUE = Pattern.compile("\"([^\"]*)\"");
    private static final Set<String> API_ROUTE_CATEGORIES = Set.of(
            "public-sdk-ingress",
            "public-sdk-read",
            "operator-command",
            "console-diagnostics",
            "internal-debug",
            "remove-or-merge"
    );

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

        assertTrue(!Files.exists(provider),
                "control-console task/worker scenario data must live outside server main source");
        assertTrue(!Files.exists(configuration),
                "control-console scenario metadata must use explicit control-plane seed/import, not dev startup configuration");
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

        assertTrue(applicationConfig.contains("default: durable-local"),
                "application.yml must use Spring's default profile support for no-arg durable-local startup");
        assertTrue(!applicationConfig.contains("active: local"),
                "application.yml must not activate the old local profile");
        assertTrue(!source.contains("System.setProperty(\"spring.profiles.active\""),
                "server main must not overwrite explicit profiles from env, args, or Spring config");
    }

    @Test
    void serverRunnableBeansAreAvailableForMemoryLocalAndDurableLocal() throws IOException {
        Path application = SERVER_MAIN_SOURCE_ROOT.resolve("com/xa/mass/server/XaMassServerApplication.java");
        String source = Files.readString(application, StandardCharsets.UTF_8);

        assertTrue(source.contains("@Profile({\"memory-local\", \"durable-local\"})"),
                "server runtime beans must be selectable through both memory-local and durable-local profiles");
        assertTrue(!source.contains("@Profile(\"memory-local\")\n    public JdbcStorageRuntime"),
                "server JDBC storage runtime must not be memory-local-only");
        assertTrue(!source.contains("@Profile(\"memory-local\")\n    public MassSdkApplication"),
                "embedded SDK runtime application must not be memory-local-only");
    }

    @Test
    void durableLocalProfileKeepsStorageRuntimeAndTraceLayersSeparate() throws IOException {
        String durableLocalConfig = Files.readString(Path.of("src/main/resources/application-durable-local.yml"),
                StandardCharsets.UTF_8);

        assertTrue(durableLocalConfig.contains("mode: jdbc-sqlite"),
                "durable-local profile must select SQLite control-plane storage");
        assertTrue(durableLocalConfig.contains("mode: redis"),
                "durable-local profile must select Redis runtime");
        assertTrue(durableLocalConfig.contains("store: redis"),
                "durable-local profile must select Redis transport delivery/presence stores");
        assertTrue(!durableLocalConfig.contains("trace"),
                "durable-local profile convergence must not add trace DB ingestion settings");
    }

    @Test
    void durableLocalInfraAssemblyFailsClosedInsteadOfFallingBackToMemory() throws IOException {
        Path application = SERVER_MAIN_SOURCE_ROOT.resolve("com/xa/mass/server/XaMassServerApplication.java");
        String applicationSource = Files.readString(application, StandardCharsets.UTF_8);
        Path storeConfiguration = SERVER_MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/server/config/ServerControlPlaneStoreConfiguration.java");
        String storeConfigurationSource = Files.readString(storeConfiguration, StandardCharsets.UTF_8);
        String disabledJdbcFallback = "JdbcStorageRuntime" + "::disabled";
        String taskWorkFallback = "getIfAvailable(" + "InMemoryTaskWorkRuntime::new)";
        String taskResultFallback = "getIfAvailable(" + "InMemoryTaskResultRuntime::new)";
        String memoryNullSwitch = "case \"\", \"memory\" -> " + "null";
        String memoryRuntimeSwitch = "case \"\", \"memory\" -> " + "new InMemory";

        assertTrue(!storeConfigurationSource.contains("ObjectProvider<JdbcStorageRuntime>"),
                "server control-plane stores must require JdbcStorageRuntime directly");
        assertTrue(!storeConfigurationSource.contains(disabledJdbcFallback),
                "server control-plane stores must not synthesize a disabled JDBC runtime fallback");
        assertTrue(!applicationSource.contains(taskWorkFallback),
                "durable-local runtime assembly must not fallback to in-memory task work runtime when the bean is missing");
        assertTrue(!applicationSource.contains(taskResultFallback),
                "durable-local runtime assembly must not fallback to in-memory task result runtime when the bean is missing");
        assertTrue(!applicationSource.contains(memoryNullSwitch),
                "durable-local transport resolver must not encode memory as a null/default factory fallback");
        assertTrue(!applicationSource.contains(memoryRuntimeSwitch),
                "durable-local runtime resolver must not encode memory as the default runtime branch");
        assertTrue(applicationSource.contains("durable-local requires mass.storage.mode to be JDBC-enabled"),
                "durable-local storage mode must fail closed when it is memory or disabled");
        assertTrue(applicationSource.contains("requireDurableLocalInfraMode(\"mass.runtime.mode\", \"redis\""),
                "durable-local runtime mode must fail closed unless Redis is selected");
        assertTrue(applicationSource.contains("requireDurableLocalInfraMode(\"mass.transport.delivery.store\", \"redis\""),
                "durable-local transport delivery must fail closed unless Redis is selected");
        assertTrue(applicationSource.contains("requireDurableLocalInfraMode(\"mass.transport.presence.store\", \"redis\""),
                "durable-local transport presence must fail closed unless Redis is selected");
    }

    @Test
    void serverApiObservabilityUsesActiveLogbackAndDedicatedFailureLane() throws IOException {
        Path activeLogback = Path.of("src/main/resources/logback.xml");
        String activeConfig = Files.readString(activeLogback, StandardCharsets.UTF_8);
        Path inactiveJsonLogback = Path.of("src/main/resources/logback-json.xml");

        assertTrue(!Files.exists(inactiveJsonLogback),
                "inactive logback-json.xml must not remain as an editable server logging config");
        assertTrue(activeConfig.contains("SERVER_API_FAILURE_FILE"),
                "active logback.xml must route SERVER_API_FAILURE events to a dedicated appender");
        assertTrue(activeConfig.contains("xa-mass-server-api-failure.log"),
                "active logback.xml must name the API failure log file");
        assertTrue(activeConfig.contains("com.xa.mass.api.observability.ServerApiFailureLogger"),
                "active logback.xml must bind the dedicated API failure logger");
        assertTrue(activeConfig.contains("SizeAndTimeBasedRollingPolicy"),
                "active logback.xml must use size-and-time rolling retention");
        assertTrue(!activeConfig.contains("SizeAndTimeBasedFNATP"),
                "active logback.xml must not use deprecated SizeAndTimeBasedFNATP");
        assertTrue(activeConfig.contains("<totalSizeCap>"),
                "rolling file appenders must set totalSizeCap so logs do not grow without bound");
    }

    @Test
    void serverApiFailureObservabilityDoesNotReadSensitiveRequestInputs() throws IOException {
        Path observabilityRoot = SERVER_MAIN_SOURCE_ROOT.resolve("com/xa/mass/api/observability");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(observabilityRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            String source = Files.readString(path, StandardCharsets.UTF_8);
                            if (source.contains("getHeader(\"Authorization\")")
                                    || source.contains("getInputStream(")
                                    || source.contains("getReader(")
                                    || source.contains("getQueryString(")
                                    || source.contains("getParameter(")
                                    || source.contains("getParameterMap(")) {
                                violations.add(path + " reads sensitive request input in the failure logging lane");
                            }
                        } catch (IOException e) {
                            violations.add(path + " could not be read: " + e.getMessage());
                        }
                    });
        }

        assertTrue(violations.isEmpty(),
                "server API failure observability must use request attributes/final status, not raw "
                        + "authorization headers, request body, query string, or parameters:\n"
                        + String.join("\n", violations));
    }

    @Test
    void endpointMetricsUseActuatorWithoutHandRolledHighCardinalityMeters() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        String memoryLocalConfig = Files.readString(Path.of("src/main/resources/application-memory-local.yml"),
                StandardCharsets.UTF_8);
        String durableLocalConfig = Files.readString(Path.of("src/main/resources/application-durable-local.yml"),
                StandardCharsets.UTF_8);
        List<String> violations = new ArrayList<>();

        if (!pom.contains("spring-boot-starter-actuator")) {
            violations.add("xa-mass-server pom.xml does not include spring-boot-starter-actuator");
        }
        if (pom.contains("micrometer-registry-prometheus")) {
            violations.add("Prometheus registry must remain a later operator-deployment decision");
        }
        if (!memoryLocalConfig.contains("include: health,metrics")) {
            violations.add("memory-local profile must expose health and metrics actuator endpoints");
        }
        if (!durableLocalConfig.contains("include: health") || durableLocalConfig.contains("include: health,metrics")) {
            violations.add("durable-local profile must expose health only by default");
        }

        try (Stream<Path> paths = Files.walk(SERVER_MAIN_SOURCE_ROOT)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            String source = Files.readString(path, StandardCharsets.UTF_8);
                            if ((source.contains("MeterRegistry")
                                    || source.contains("Timer.builder")
                                    || source.contains("Counter.builder"))
                                    && Stream.of("http.server.requests",
                                                    ".tag(\"principalId\"",
                                                    ".tag(\"traceId\"",
                                                    ".tag(\"taskId\"",
                                                    ".tag(\"workerId\"",
                                                    ".tag(\"rawUrl\"",
                                                    ".tag(\"query\"")
                                            .anyMatch(source::contains)) {
                                violations.add(path
                                        + " defines hand-rolled/high-cardinality endpoint metrics");
                            }
                        } catch (IOException e) {
                            violations.add(path + " could not be read: " + e.getMessage());
                        }
                    });
        }

        assertTrue(violations.isEmpty(),
                "endpoint success/latency metrics must use actuator http.server.requests first pass, "
                        + "without hand-rolled high-cardinality endpoint meters:\n"
                        + String.join("\n", violations));
    }

    @Test
    void sampleBootstrapHttpIsNotActiveServerApi() throws IOException {
        Path repoRoot = Path.of("..").toAbsolutePath().normalize();
        Path serverController = SERVER_MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/api/sample/SampleBootstrapController.java");
        Path workerPackController = repoRoot.resolve(
                "integrations/xa-mass-worker-pack/src/main/java/com/xa/mass/workerpack/sample/api/SampleBootstrapController.java");
        String memoryLocalConfig = Files.readString(Path.of("src/main/resources/application-memory-local.yml"),
                StandardCharsets.UTF_8);
        String durableLocalConfig = Files.readString(Path.of("src/main/resources/application-durable-local.yml"),
                StandardCharsets.UTF_8);
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(SERVER_MAIN_SOURCE_ROOT)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            String source = Files.readString(path, StandardCharsets.UTF_8);
                            if (source.contains("/sample-api/bootstrap")
                                    || source.contains("X-Sample-Bootstrap-Key")
                                    || source.contains("sample.bootstrap")) {
                                violations.add(path + " contains retired sample bootstrap HTTP vocabulary");
                            }
                        } catch (IOException e) {
                            violations.add(path + " could not be read: " + e.getMessage());
                        }
                    });
        }

        assertTrue(!Files.exists(serverController),
                "sample bootstrap HTTP controller must not remain active server API");
        assertTrue(!Files.exists(workerPackController),
                "sample bootstrap HTTP controller must stay server-owned, not worker-pack-owned");
        assertTrue(violations.isEmpty(),
                "server main source must not expose retired sample bootstrap HTTP path:\n"
                        + String.join("\n", violations));
        assertTrue(!memoryLocalConfig.contains("sample.bootstrap")
                        && !memoryLocalConfig.contains("bootstrap:\n    enabled: true"),
                "memory-local profile must not enable retired sample bootstrap HTTP");
        assertTrue(!durableLocalConfig.contains("sample.bootstrap")
                        && !durableLocalConfig.contains("bootstrap:\n    enabled: false"),
                "durable-local profile must not carry retired sample bootstrap HTTP config");
        assertTrue(!durableLocalConfig.contains("integrations/samples/dev/scenario")
                        && !durableLocalConfig.contains("control-plane-seed/control-console-scenario.json"),
                "durable-local profile must not default to checked-in scenario seeds that contain devOnly API-key raw secrets");
    }

    @Test
    void apiKeyLifecycleDoesNotDependOnBroadLegacyResourceOperations() throws IOException {
        Path service = SERVER_MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/api/auth/apikey/ApiKeyCredentialService.java");
        String source = Files.readString(service, StandardCharsets.UTF_8);

        assertTrue(!source.contains("import com.xa.mass.sdk." + "Sub" + "mitterOperations"),
                "ApiKeyCredentialService must write auth projection through a narrow projection port, "
                        + "not broad legacy resource operations");
        assertTrue(source.contains("CredentialAuthProjectionWriter"),
                "ApiKeyCredentialService must depend on CredentialAuthProjectionWriter for auth projection writes");
    }

    @Test
    void removedLegacyCredentialRegistryDoesNotReappear() throws IOException {
        Path registry = REPO_ROOT.resolve(
                "sdk/xa-mass-embedded-sdk-api/src/main/java/com/xa/mass/sdk/auth/" + "Sub" + "mitterRegistry.java");
        assertTrue(!Files.exists(registry), "legacy credential registry must not reappear");
    }

    @Test
    void apiKeyViewerSessionsAreNotJdbcControlPlaneStores() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(SERVER_MAIN_SOURCE_ROOT)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            String source = Files.readString(path, StandardCharsets.UTF_8);
                            String legacySessionTable = "sub" + "mitter_viewer_session";
                            if (source.contains("JdbcApiKeyViewerSessionStore")
                                    || source.contains("ApiKeyViewerSessionStore") && source.contains("xa_" + legacySessionTable)
                                    || source.contains("ApiKeyViewerSessionStore") && source.contains(legacySessionTable)) {
                                violations.add(path + " treats ApiKeyViewerSessionStore as JDBC/control-plane storage");
                            }
                        } catch (IOException e) {
                            violations.add(path + " could not be read: " + e.getMessage());
                        }
                    });
        }

        assertTrue(violations.isEmpty(),
                "API-key viewer sessions must remain volatile session state, not JDBC stores:\n"
                        + String.join("\n", violations));
    }

    @Test
    void stableServerControlPlaneMemoryStoresAreExplicitlyAssembled() throws IOException {
        Map<String, String> storeFiles = Map.of(
                "InMemoryApiKeyApplicationStore", "com/xa/mass/api/auth/apikey/InMemoryApiKeyApplicationStore.java",
                "InMemoryApiKeyCredentialStore", "com/xa/mass/api/auth/apikey/InMemoryApiKeyCredentialStore.java",
                "InMemoryUserRolePermissionStore", "com/xa/mass/api/auth/iam/InMemoryUserRolePermissionStore.java",
                "InMemoryApiKeyViewerSessionStore", "com/xa/mass/api/auth/session/InMemoryApiKeyViewerSessionStore.java",
                "InMemoryApiUsageLedgerStore", "com/xa/mass/api/auth/usage/InMemoryApiUsageLedgerStore.java",
                "InMemoryWorkerRegistrationObservationStore",
                "com/xa/mass/api/worker/registration/InMemoryWorkerRegistrationObservationStore.java"
        );
        List<String> violations = new ArrayList<>();
        storeFiles.forEach((symbol, relativePath) -> {
            try {
                String source = Files.readString(SERVER_MAIN_SOURCE_ROOT.resolve(relativePath), StandardCharsets.UTF_8);
                if (source.contains("@Component") || source.contains("org.springframework.stereotype.Component")) {
                    violations.add(symbol + " is component-scanned instead of assembled by ServerControlPlaneStoreConfiguration");
                }
            } catch (IOException e) {
                violations.add(relativePath + " could not be read: " + e.getMessage());
            }
        });

        Path configuration = SERVER_MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/server/config/ServerControlPlaneStoreConfiguration.java");
        String configSource = Files.readString(configuration, StandardCharsets.UTF_8);
        storeFiles.keySet().forEach(symbol -> {
            if (!configSource.contains("new " + symbol + "(")
                    && !configSource.contains(symbol + ".bootstrapDefaults()")) {
                violations.add("ServerControlPlaneStoreConfiguration does not explicitly assemble " + symbol);
            }
        });

        assertTrue(violations.isEmpty(),
                "stable server control-plane memory stores must be explicit beans, not component-selected:\n"
                        + String.join("\n", violations));
    }

    @Test
    void serverOwnedControlPlaneMigrationsStayInServerResources() throws IOException {
        Path runner = SERVER_MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/server/config/ServerControlPlaneMigrationRunner.java");
        String runnerSource = Files.readString(runner, StandardCharsets.UTF_8);

        assertTrue(runnerSource.contains("classpath:db/migration/server-control-plane"),
                "server API-key/IAM/usage migration runner must load server-owned migration resources");
        assertTrue(runnerSource.contains("flyway_server_control_plane_schema_history"),
                "server-owned migrations must use a separate Flyway history table");
        assertTrue(!runnerSource.contains("classpath:db/migration/control-plane"),
                "server-owned API/IAM/usage migrations must not reuse platform_infra control-plane migration location");
    }

    @Test
    void startupReadinessRunsBeforeFullStackRuntimeStart() throws IOException {
        String serverSource = Files.readString(
                SERVER_MAIN_SOURCE_ROOT.resolve("com/xa/mass/server/XaMassServerApplication.java"),
                StandardCharsets.UTF_8);
        String seedSource = Files.readString(
                SERVER_MAIN_SOURCE_ROOT.resolve(
                        "com/xa/mass/server/bootstrap/seed/ControlPlaneSeedImportConfiguration.java"),
                StandardCharsets.UTF_8);
        String readinessSource = Files.readString(
                SERVER_MAIN_SOURCE_ROOT.resolve(
                        "com/xa/mass/api/auth/operator/OperatorAuthReadinessGuard.java"),
                StandardCharsets.UTF_8);

        assertTrue(Pattern.compile("@Order\\(0\\)\\s+@ConditionalOnProperty[\\s\\S]*controlPlaneEarlySeedImportRunner")
                        .matcher(seedSource)
                        .find(),
                "catalog/operator seed import must run before auth readiness and runtime startup");
        assertTrue(Pattern.compile("@Order\\(1\\)\\s+public final class OperatorAuthReadinessGuard")
                        .matcher(readinessSource)
                        .find(),
                "operator auth readiness must fail before full-stack runtime startup");
        assertTrue(Pattern.compile("@Order\\(2\\)\\s+public CommandLineRunner fullStackStarter")
                        .matcher(serverSource)
                        .find(),
                "full-stack runtime must start after seed/import and startup readiness checks");
        assertTrue(Pattern.compile("@Order\\(3\\)\\s+@ConditionalOnProperty[\\s\\S]*controlPlaneRuleSeedImportRunner")
                        .matcher(seedSource)
                        .find(),
                "rule seed import must run after full-stack runtime startup");
        assertTrue(Pattern.compile("@Order\\(4\\)[\\s\\S]*taskReviewReadModelFinalityListener")
                        .matcher(serverSource)
                        .find(),
                "review finality listener must register after runtime startup and rule seed import");
        assertTrue(Pattern.compile("@Order\\(4\\)[\\s\\S]*taskReviewReadModelAttemptClosedListener")
                        .matcher(serverSource)
                        .find(),
                "review attempt listener must register after runtime startup and rule seed import");
    }

    @Test
    void apiKeyLifecycleDoesNotProjectThroughLegacyCredentialPayload() throws IOException {
        String source = Files.readString(
                SERVER_MAIN_SOURCE_ROOT.resolve("com/xa/mass/api/auth/apikey/ApiKeyCredentialService.java"),
                StandardCharsets.UTF_8);

        assertTrue(!source.contains("Sub" + "mitterRegistration"),
                "API-key lifecycle must project credential principals without the legacy credential payload");
        assertTrue(source.contains("CredentialPrincipalRegistration"),
                "API-key lifecycle should use the credential-principal projection payload");
    }

    @Test
    void sampleControlPlaneSeedsUseApiKeysOnly() throws IOException {
        List<Path> seedFiles = List.of(
                SERVER_MAIN_SOURCE_ROOT.resolve("../resources/control-plane-seed/control-console-scenario.json")
                        .normalize(),
                REPO_ROOT.resolve("integrations/samples/dev/scenario/bootstrap.json")
        );
        List<String> violations = new ArrayList<>();
        for (Path seedFile : seedFiles) {
            String source = Files.readString(seedFile, StandardCharsets.UTF_8);
            String legacySeedField = "\"submit" + "ters\"";
            if (source.contains(legacySeedField)) {
                violations.add(seedFile + " still uses legacy credential seed field");
            }
            if (!source.contains("\"apiKeys\"")) {
                violations.add(seedFile + " does not define apiKeys seed field");
            }
            String[] lines = source.split("\\R");
            for (int index = 0; index < lines.length; index++) {
                if (lines[index].contains("\"rawSecret\"")
                        && (index == 0 || !lines[index - 1].contains("\"devOnly\": true"))) {
                    violations.add(seedFile + " rawSecret is missing adjacent devOnly=true marker near line " + (index + 1));
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "sample control-plane seeds must use API-key seed shape:\n" + String.join("\n", violations));
    }

    @Test
    void serverApiIamUsageSchemaDoesNotMoveToPlatformInfraMigrations() throws IOException {
        List<String> violations = new ArrayList<>();
        Path platformMigrations = REPO_ROOT.resolve(
                "platform_infra/mass-storage-jdbc/src/main/resources/db/migration");
        try (Stream<Path> paths = Files.walk(platformMigrations)) {
            paths.filter(Files::isRegularFile)
                    .forEach(path -> {
                        try {
                            String source = Files.readString(path, StandardCharsets.UTF_8);
                            if (source.contains("xa_api_key_")
                                    || source.contains("xa_iam_")
                                    || source.contains("xa_operator_credential")
                                    || source.contains("xa_api_usage_")
                                    || source.contains("xa_worker_registration_")
                                    || source.contains("sub" + "mitter_viewer_session")) {
                                violations.add(path
                                        + " contains server-owned API/IAM/operator/session/usage/worker-observation schema");
                            }
                        } catch (IOException e) {
                            violations.add(path + " could not be read: " + e.getMessage());
                        }
                    });
        }

        Path serverMigrations = REPO_ROOT.resolve(
                "xa-mass-server/src/main/resources/db/migration/server-control-plane");
        try (Stream<Path> paths = Files.walk(serverMigrations)) {
            paths.filter(Files::isRegularFile)
                    .forEach(path -> {
                        try {
                            String source = Files.readString(path, StandardCharsets.UTF_8);
                            if (source.contains("sub" + "mitter_viewer_session")) {
                                violations.add(path + " persists API-key viewer sessions in JDBC");
                            }
                            if (source.contains("xa_worker_registration_")
                                    && (source.contains("heartbeat")
                                    || source.contains("online")
                                    || source.contains("lock")
                                    || source.contains("lease")
                                    || source.contains("route_bucket")
                                    || source.contains("dispatch"))) {
                                violations.add(path + " stores runtime worker truth in registration observation schema");
                            }
                        } catch (IOException e) {
                            violations.add(path + " could not be read: " + e.getMessage());
                        }
                    });
        }

        assertTrue(violations.isEmpty(),
                "server-owned API-key/IAM/operator/usage/worker-observation schema must stay in xa-mass-server and "
                        + "API-key viewer sessions must stay out of JDBC:\n"
                        + String.join("\n", violations));
    }

    @Test
    void catalogMetadataSchemaStaysInPlatformInfraMigrations() throws IOException {
        List<String> violations = new ArrayList<>();
        Path serverMigrations = REPO_ROOT.resolve(
                "xa-mass-server/src/main/resources/db/migration/server-control-plane");
        try (Stream<Path> paths = Files.walk(serverMigrations)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".sql"))
                    .forEach(path -> {
                        try {
                            String source = Files.readString(path, StandardCharsets.UTF_8);
                            if (source.contains("xa_catalog_")) {
                                violations.add(path + " contains catalog metadata schema");
                            }
                        } catch (IOException e) {
                            violations.add(path + " could not be read: " + e.getMessage());
                        }
                    });
        }

        Path platformCatalogMigration = REPO_ROOT.resolve(
                "platform_infra/mass-storage-jdbc/src/main/resources/db/migration/control-plane/"
                        + "V4__create_catalog_tables.sql");
        String platformSource = Files.readString(platformCatalogMigration, StandardCharsets.UTF_8);
        for (String table : List.of("xa_catalog_event", "xa_catalog_project", "xa_catalog_project_event")) {
            if (!platformSource.contains(table)) {
                violations.add(platformCatalogMigration + " does not define " + table);
            }
        }

        assertTrue(violations.isEmpty(),
                "catalog metadata schema must belong to platform_infra/mass-storage-jdbc, "
                        + "not server-owned migrations:\n"
                        + String.join("\n", violations));
    }

    @Test
    void durableCatalogAssemblyDoesNotExposeDefaultCatalogFallbackForRunnableProfiles() throws IOException {
        List<String> violations = new ArrayList<>();
        Path application = SERVER_MAIN_SOURCE_ROOT.resolve("com/xa/mass/server/XaMassServerApplication.java");
        String applicationSource = Files.readString(application, StandardCharsets.UTF_8);
        if (!applicationSource.contains("CatalogMetadataProjection.restoreIntoApplication(catalogMetadataStore, app)")) {
            violations.add("XaMassServerApplication does not restore durable catalog metadata into MassSdkApplication");
        }
        if (!applicationSource.contains("jdbcStorageRuntime.catalogMetadataStore()")) {
            violations.add("XaMassServerApplication does not assemble the JDBC catalog metadata store");
        }
        if (!applicationSource.contains("new InMemoryCatalogMetadataStore()")) {
            violations.add("XaMassServerApplication does not explicitly assemble the in-memory catalog metadata store");
        }

        Path catalogConfig = SERVER_MAIN_SOURCE_ROOT.resolve("com/xa/mass/api/config/CatalogConfiguration.java");
        String catalogConfigSource = Files.readString(catalogConfig, StandardCharsets.UTF_8);
        if (!catalogConfigSource.contains("@Profile(\"!memory-local & !durable-local\")")) {
            violations.add("CatalogConfiguration default fallback must be excluded from memory-local/durable-local profiles");
        }

        Map<String, String> routeControllers = Map.of(
                "TaskApiController",
                "com/xa/mass/api/internal/TaskApiController.java",
                "InternalDebugTaskInvocationController",
                "com/xa/mass/api/internal/InternalDebugTaskInvocationController.java"
        );
        routeControllers.forEach((name, relativePath) -> {
            try {
                String source = Files.readString(SERVER_MAIN_SOURCE_ROOT.resolve(relativePath), StandardCharsets.UTF_8);
                if (source.contains("DefaultProjectEventCatalogFactory")
                        || source.contains("catalog == null")
                        || !source.contains("Objects.requireNonNull(catalog, \"catalog\")")) {
                    violations.add(name + " must require an injected ControlPlaneCatalog instead of creating a fallback");
                }
            } catch (IOException e) {
                violations.add(relativePath + " could not be read: " + e.getMessage());
            }
        });

        assertTrue(violations.isEmpty(),
                "server memory-local/durable-local catalog truth must not be masked by default/demo catalog fallback:\n"
                        + String.join("\n", violations));
    }

    @Test
    void durableLocalAuthAssemblyDoesNotCreateImplicitOperatorMemoryFallbacks() throws IOException {
        Path allowedAssembly = SERVER_MAIN_SOURCE_ROOT.resolve(
                "com/xa/mass/server/config/ServerControlPlaneStoreConfiguration.java");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(SERVER_MAIN_SOURCE_ROOT)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.equals(allowedAssembly))
                    .forEach(path -> {
                        try {
                            String source = Files.readString(path, StandardCharsets.UTF_8);
                            if (source.contains("new DefaultOperatorPrincipalDirectory(")
                                    || source.contains("new ApiAuthService(")
                                    || source.contains("InMemoryUserRolePermissionStore.bootstrapDefaults()")) {
                                violations.add(path + " bypasses explicit server auth/store assembly");
                            }
                        } catch (IOException e) {
                            violations.add(path + " could not be read: " + e.getMessage());
                        }
                    });
        }

        assertTrue(violations.isEmpty(),
                "durable-local auth wiring must not create implicit operator memory fallbacks outside explicit assembly:\n"
                        + String.join("\n", violations));
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

    @Test
    void serverApiRoutesStayClassifiedByApiSurfaceInventory() throws IOException {
        Path inventory = REPO_ROOT.resolve("xa-mass-server/doc/API_SURFACE_INVENTORY.md");
        Map<String, String> inventoryRoutes = readRouteInventory(inventory);
        Set<String> controllerRoutes = collectControllerApiRoutes();
        List<String> violations = new ArrayList<>();

        controllerRoutes.forEach(route -> {
            if (!inventoryRoutes.containsKey(route)) {
                violations.add(route + " is missing from " + REPO_ROOT.relativize(inventory));
            }
        });
        inventoryRoutes.keySet().forEach(route -> {
            if (!controllerRoutes.contains(route)) {
                violations.add(route + " is listed in " + REPO_ROOT.relativize(inventory)
                        + " but has no matching controller route");
            }
        });
        inventoryRoutes.forEach((route, category) -> {
            if (!API_ROUTE_CATEGORIES.contains(category)) {
                violations.add(route + " uses unknown API route category " + category);
            }
            String path = route.substring(route.indexOf(' ') + 1);
            if (path.startsWith("/api/v1/runtime/")
                    && !Set.of("console-diagnostics", "operator-command", "remove-or-merge").contains(category)) {
                violations.add(route + " is a runtime route but is categorized as " + category);
            }
            if (path.startsWith("/internal/v1/")
                    && !Set.of("internal-debug", "console-diagnostics").contains(category)) {
                violations.add(route + " is an internal route but is categorized as " + category);
            }
            if (path.startsWith("/api/v1/runtime/workers/")
                    && (path.endsWith("/capability-reports")
                    || path.endsWith("/state-reports")
                    || path.matches("^/api/v1/runtime/workers/\\{workerId}/commands/\\{commandId}/ack$"))
                    && !"remove-or-merge".equals(category)) {
                violations.add(route + " duplicates worker data-plane ingress and must stay remove-or-merge");
            }
        });

        assertTrue(violations.isEmpty(),
                "server API routes must stay classified by the active API surface inventory:\n"
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

    private static Map<String, String> readRouteInventory(Path inventory) throws IOException {
        String source = Files.readString(inventory, StandardCharsets.UTF_8);
        Map<String, String> routes = new LinkedHashMap<>();
        boolean inRouteInventory = false;
        for (String line : source.split("\\R")) {
            if (line.equals("## Route Inventory")) {
                inRouteInventory = true;
                continue;
            }
            if (inRouteInventory && line.startsWith("## ")) {
                break;
            }
            if (!inRouteInventory || !line.startsWith("|")) {
                continue;
            }
            Matcher matcher = TABLE_ROW.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String method = matcher.group(1).trim();
            String route = matcher.group(2).trim();
            String category = matcher.group(4).trim();
            if ("Method".equals(method) || "---".equals(method)) {
                continue;
            }
            routes.put(method + " " + route, category);
        }
        return routes;
    }

    private static Set<String> collectControllerApiRoutes() throws IOException {
        Set<String> routes = new LinkedHashSet<>();
        Path controllerRoot = SERVER_MAIN_SOURCE_ROOT.resolve("com/xa/mass/api/internal");
        try (Stream<Path> paths = Files.walk(controllerRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith("Controller.java"))
                    .filter(path -> !path.getFileName().toString().equals("FrontendConsoleController.java"))
                    .forEach(path -> collectControllerApiRoutes(path, routes));
        }
        return routes;
    }

    private static void collectControllerApiRoutes(Path path, Set<String> routes) {
        String source;
        try {
            source = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + path + ": " + e.getMessage(), e);
        }
        String basePath = "";
        Matcher classMapping = CLASS_REQUEST_MAPPING.matcher(source);
        if (classMapping.find()) {
            basePath = classMapping.group(1);
        }
        Matcher methodMapping = METHOD_MAPPING.matcher(source);
        while (methodMapping.find()) {
            String annotation = methodMapping.group(1);
            String mappingArguments = methodMapping.group(2);
            String methodPath = extractMethodPath(mappingArguments);
            if (methodPath == null) {
                continue;
            }
            String route = joinPaths(basePath, methodPath);
            if (route.startsWith("/api/v1/")
                    || route.startsWith("/internal/v1/")
                    || route.startsWith("/worker-api/v1/")) {
                routes.add(httpMethod(annotation) + " " + route);
            }
        }
    }

    private static String extractMethodPath(String mappingArguments) {
        if (mappingArguments == null || mappingArguments.isBlank()) {
            return "";
        }
        Matcher matcher = FIRST_QUOTED_VALUE.matcher(mappingArguments);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1);
    }

    private static String joinPaths(String basePath, String methodPath) {
        if (methodPath == null || methodPath.isBlank()) {
            return basePath;
        }
        if (basePath == null || basePath.isBlank()) {
            return methodPath;
        }
        if (basePath.endsWith("/") && methodPath.startsWith("/")) {
            return basePath + methodPath.substring(1);
        }
        if (!basePath.endsWith("/") && !methodPath.startsWith("/")) {
            return basePath + "/" + methodPath;
        }
        return basePath + methodPath;
    }

    private static String httpMethod(String annotation) {
        return switch (annotation) {
            case "GetMapping" -> "GET";
            case "PostMapping" -> "POST";
            case "PatchMapping" -> "PATCH";
            case "DeleteMapping" -> "DELETE";
            default -> throw new IllegalArgumentException("Unsupported mapping annotation: " + annotation);
        };
    }
}
