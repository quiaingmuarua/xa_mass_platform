package com.xa.mass.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ScenarioCredentialBootstrapMain {

    private ScenarioCredentialBootstrapMain() {
    }

    public static void main(String[] args) throws Exception {
        ScenarioCredentialBootstrapOptions options = ScenarioCredentialBootstrapOptions.parse(args);
        if (options.help()) {
            System.out.println(ScenarioCredentialBootstrapOptions.helpText());
            return;
        }
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ScenarioCredentialBootstrapper bootstrapper = new ScenarioCredentialBootstrapper(
                options,
                objectMapper,
                HttpClient.newBuilder()
                        .connectTimeout(options.connectTimeout())
                        .cookieHandler(cookieManager())
                        .build()
        );
        if (options.kind() == CredentialKind.ENV) {
            List<Path> cacheFiles = bootstrapper.prepareEnvironment();
            System.out.printf("[java-scenario-env-bootstrap] ready files=%s%n", cacheFiles);
            return;
        }
        Path cacheFile = bootstrapper.prepare();
        System.out.printf("[java-scenario-env-bootstrap] %s API key ready file=%s%n",
                options.kind().label(), cacheFile);
    }

    private static CookieManager cookieManager() {
        CookieManager cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        return cookieManager;
    }

    record ScenarioCredentialBootstrapOptions(CredentialKind kind,
                                              String baseUrl,
                                              Path apiKeyFile,
                                              String operatorUser,
                                              String operatorPassword,
                                              String principalId,
                                              String createdForUserId,
                                              List<String> projectScopes,
                                              List<String> eventScopes,
                                              List<String> permissions,
                                              Duration connectTimeout,
                                              Duration requestTimeout,
                                              boolean createIfMissing,
                                              boolean refreshStaleCache,
                                              boolean help) {

        private static final String DEFAULT_BASE_URL = "http://127.0.0.1:8088";
        private static final String DEFAULT_OPERATOR_USER = "ops-admin";
        private static final String DEFAULT_TASK_PRINCIPAL_ID = "crawler-task-producer-local";
        private static final String DEFAULT_WORKER_PRINCIPAL_ID = "scenario-worker-local";
        private static final Path DEFAULT_TASK_API_KEY_FILE =
                Path.of("integrations/xa-mass-scenario-launcher/examples/secrets/task-api-key.txt");
        private static final Path DEFAULT_WORKER_API_KEY_FILE =
                Path.of("integrations/xa-mass-scenario-launcher/examples/secrets/worker-api-key.txt");
        private static final Path DEFAULT_SCENARIO_DIR =
                Path.of("integrations/samples/dev/scenario");
        private static final Path DEFAULT_CATALOG_MANIFEST =
                Path.of("integrations/xa-mass-scenario-launcher/examples/scenario.catalog.seed.json");
        private static final Path DEFAULT_RULES_MANIFEST =
                Path.of("integrations/samples/dev/scenario/rules.json");

        static ScenarioCredentialBootstrapOptions parse(String[] args) {
            CredentialKind kind = CredentialKind.ENV;
            Path configPath = null;
            String cliBaseUrl = null;
            Path cliApiKeyFile = null;
            String operatorUser = firstNonBlank(System.getenv("MASS_OPERATOR_USER"), DEFAULT_OPERATOR_USER);
            String operatorPassword = firstNonBlank(System.getenv("MASS_OPERATOR_PASSWORD"), DEFAULT_OPERATOR_USER);
            String principalId = null;
            String createdForUserId = firstNonBlank(System.getenv("MASS_SCENARIO_TASK_USER_ID"),
                    DEFAULT_OPERATOR_USER);
            List<String> projectScopes = null;
            List<String> eventScopes = null;
            boolean createIfMissing = true;
            boolean refreshStaleCache = true;
            boolean help = false;

            for (int index = 0; index < args.length; index++) {
                String arg = args[index];
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    help = true;
                } else if ("--config".equals(arg)) {
                    configPath = Path.of(requiredArg(args, index, arg));
                    index++;
                } else if (arg.startsWith("--config=")) {
                    configPath = Path.of(arg.substring("--config=".length()));
                } else if ("--base-url".equals(arg)) {
                    cliBaseUrl = requiredArg(args, index, arg);
                    index++;
                } else if (arg.startsWith("--base-url=")) {
                    cliBaseUrl = arg.substring("--base-url=".length());
                } else if ("--kind".equals(arg)) {
                    kind = CredentialKind.parse(requiredArg(args, index, arg));
                    index++;
                } else if (arg.startsWith("--kind=")) {
                    kind = CredentialKind.parse(arg.substring("--kind=".length()));
                } else if ("--api-key-file".equals(arg) || "--task-api-key-file".equals(arg)) {
                    cliApiKeyFile = Path.of(requiredArg(args, index, arg));
                    index++;
                } else if (arg.startsWith("--api-key-file=")) {
                    cliApiKeyFile = Path.of(arg.substring("--api-key-file=".length()));
                } else if (arg.startsWith("--task-api-key-file=")) {
                    cliApiKeyFile = Path.of(arg.substring("--task-api-key-file=".length()));
                } else if ("--operator-user".equals(arg)) {
                    operatorUser = requiredArg(args, index, arg);
                    index++;
                } else if (arg.startsWith("--operator-user=")) {
                    operatorUser = arg.substring("--operator-user=".length());
                } else if ("--operator-password".equals(arg)) {
                    operatorPassword = requiredArg(args, index, arg);
                    index++;
                } else if (arg.startsWith("--operator-password=")) {
                    operatorPassword = arg.substring("--operator-password=".length());
                } else if ("--principal-id".equals(arg)) {
                    principalId = requiredArg(args, index, arg);
                    index++;
                } else if (arg.startsWith("--principal-id=")) {
                    principalId = arg.substring("--principal-id=".length());
                } else if ("--created-for-user".equals(arg)) {
                    createdForUserId = requiredArg(args, index, arg);
                    index++;
                } else if (arg.startsWith("--created-for-user=")) {
                    createdForUserId = arg.substring("--created-for-user=".length());
                } else if ("--project".equals(arg)) {
                    projectScopes = csv(requiredArg(args, index, arg), projectScopes);
                    index++;
                } else if (arg.startsWith("--project=")) {
                    projectScopes = csv(arg.substring("--project=".length()), projectScopes);
                } else if ("--event-code".equals(arg)) {
                    eventScopes = csv(requiredArg(args, index, arg), eventScopes);
                    index++;
                } else if (arg.startsWith("--event-code=")) {
                    eventScopes = csv(arg.substring("--event-code=".length()), eventScopes);
                } else if ("--no-create".equals(arg)) {
                    createIfMissing = false;
                } else if ("--no-refresh-stale-cache".equals(arg)) {
                    refreshStaleCache = false;
                } else {
                    throw new IllegalArgumentException("unknown argument: " + arg);
                }
            }

            ScenarioLauncherConfig.Loaded loaded = loadConfig(configPath);
            ScenarioLauncherConfig config = loaded == null ? null : loaded.config();
            String baseUrl = firstNonBlank(
                    cliBaseUrl,
                    System.getenv("MASS_BASE_URL"),
                    config == null || config.server() == null ? null : config.server().baseUrl(),
                    DEFAULT_BASE_URL
            );
            if (principalId == null || principalId.isBlank()) {
                principalId = defaultPrincipalId(kind);
            }
            if (projectScopes == null) {
                projectScopes = csv(System.getenv("MASS_SCENARIO_PROJECTS"), defaultProjectScopes(kind));
            }
            if (eventScopes == null) {
                eventScopes = csv(System.getenv("MASS_SCENARIO_EVENTS"), defaultEventScopes(kind));
            }
            Path apiKeyFile = cliApiKeyFile != null
                    ? cliApiKeyFile
                    : apiKeyFileFromConfig(loaded, kind);
            if (apiKeyFile == null) {
                apiKeyFile = defaultApiKeyFile(kind);
            }
            return new ScenarioCredentialBootstrapOptions(
                    kind,
                    normalizeBaseUrl(baseUrl),
                    apiKeyFile.toAbsolutePath().normalize(),
                    requireNonBlank(operatorUser, "operatorUser"),
                    requireNonBlank(operatorPassword, "operatorPassword"),
                    requireNonBlank(principalId, "principalId"),
                    requireNonBlank(createdForUserId, "createdForUserId"),
                    projectScopes,
                    eventScopes,
                    kind.permissions(),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(30),
                    createIfMissing,
                    refreshStaleCache,
                    help
            );
        }

        static String helpText() {
            return """
                    Usage:
                      java -cp ... com.xa.mass.scenario.ScenarioCredentialBootstrapMain [options]

                    Initializes the local scenario environment. Default env mode
                    syncs the checked-in scenario catalog/rules through server
                    control-plane APIs, then prepares the task API-key cache
                    file and workerId-bound worker credentials through operator login and
                    POST /api/v1/api-keys.

                    Options:
                      --config <path>              Scenario task config. Reads server.baseUrl and credentials.taskApiKeyFile.
                      --base-url <url>             Server HTTP base URL. Default: MASS_BASE_URL or http://127.0.0.1:8088
                      --kind <env|task|worker>     Bootstrap scope. Default: env
                      --api-key-file <path>        Cache file to validate/write.
                      --task-api-key-file <path>   Alias for --api-key-file, kept for task runs.
                      --operator-user <user>       Operator user. Default: MASS_OPERATOR_USER or ops-admin
                      --operator-password <pass>   Operator password. Default: MASS_OPERATOR_PASSWORD or ops-admin
                      --principal-id <id>          API-key principal id. Defaults by kind.
                      --created-for-user <id>      API-key owner user id. Default: MASS_SCENARIO_TASK_USER_ID or ops-admin
                      --project <csv>              Project scopes. Task default: crawlerApp. Worker default: *
                      --event-code <csv>           Event scopes. Task default: crawler.fetch-page. Worker default: *
                      --no-create                  Fail if cache is missing.
                      --no-refresh-stale-cache     Fail if cache exists but is invalid.
                      -h, --help                   Show this help.
                    """;
        }

        private static ScenarioLauncherConfig.Loaded loadConfig(Path configPath) {
            if (configPath == null) {
                return null;
            }
            try {
                return ScenarioLauncherConfig.load(configPath, new ObjectMapper().findAndRegisterModules());
            } catch (IOException e) {
                throw new IllegalArgumentException("failed to read config file: " + configPath, e);
            }
        }

        private static Path apiKeyFileFromConfig(ScenarioLauncherConfig.Loaded loaded, CredentialKind kind) {
            if ((kind != CredentialKind.TASK && kind != CredentialKind.ENV)
                    || loaded == null || loaded.config().credentials() == null) {
                return null;
            }
            String file = loaded.config().credentials().taskApiKeyFile();
            if (file == null || file.isBlank()) {
                return null;
            }
            return loaded.resolvePath(file, "credentials.taskApiKeyFile");
        }

        private static String defaultPrincipalId(CredentialKind kind) {
            String env = kind == CredentialKind.WORKER
                    ? System.getenv("MASS_SCENARIO_WORKER_PRINCIPAL_ID")
                    : System.getenv("MASS_SCENARIO_TASK_PRINCIPAL_ID");
            return firstNonBlank(env, kind == CredentialKind.WORKER
                    ? DEFAULT_WORKER_PRINCIPAL_ID
                    : DEFAULT_TASK_PRINCIPAL_ID);
        }

        private static Path defaultApiKeyFile(CredentialKind kind) {
            return kind == CredentialKind.WORKER ? DEFAULT_WORKER_API_KEY_FILE : DEFAULT_TASK_API_KEY_FILE;
        }

        private static List<String> defaultProjectScopes(CredentialKind kind) {
            return kind == CredentialKind.WORKER ? List.of("*") : List.of("crawlerApp", "deviceProbe");
        }

        private static List<String> defaultEventScopes(CredentialKind kind) {
            return kind == CredentialKind.WORKER
                    ? List.of("*")
                    : List.of("crawler.fetch-page", "stock.quote.fetch", "probe.phone.metadata");
        }

        private static List<String> csv(String value, List<String> defaultValue) {
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            List<String> values = java.util.Arrays.stream(value.split(","))
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .distinct()
                    .toList();
            return values.isEmpty() ? defaultValue : values;
        }

        private static String requiredArg(String[] args, int index, String name) {
            if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                throw new IllegalArgumentException(name + " requires a value");
            }
            return args[index + 1];
        }

        private static String firstNonBlank(String... values) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
            return null;
        }

        private static String requireNonBlank(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return value.trim();
        }

        private static String normalizeBaseUrl(String value) {
            String normalized = requireNonBlank(value, "baseUrl");
            return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
        }

        ScenarioCredentialBootstrapOptions forKind(CredentialKind targetKind) {
            if (targetKind == kind && kind != CredentialKind.ENV) {
                return this;
            }
            Path targetFile = apiKeyFileForTarget(targetKind);
            return new ScenarioCredentialBootstrapOptions(
                    targetKind,
                    baseUrl,
                    targetFile.toAbsolutePath().normalize(),
                    operatorUser,
                    operatorPassword,
                    defaultPrincipalId(targetKind),
                    createdForUserId,
                    defaultProjectScopes(targetKind),
                    defaultEventScopes(targetKind),
                    targetKind.permissions(),
                    connectTimeout,
                    requestTimeout,
                    createIfMissing,
                    refreshStaleCache,
                    false
            );
        }

        ScenarioCredentialBootstrapOptions withPrincipalId(String replacementPrincipalId) {
            return new ScenarioCredentialBootstrapOptions(
                    kind,
                    baseUrl,
                    apiKeyFile,
                    operatorUser,
                    operatorPassword,
                    requireNonBlank(replacementPrincipalId, "principalId"),
                    createdForUserId,
                    projectScopes,
                    eventScopes,
                    permissions,
                    connectTimeout,
                    requestTimeout,
                    createIfMissing,
                    refreshStaleCache,
                    help
            );
        }

        private Path apiKeyFileForTarget(CredentialKind targetKind) {
            if (kind == CredentialKind.ENV) {
                if (targetKind == CredentialKind.TASK) {
                    return apiKeyFile;
                }
                Path parent = apiKeyFile.getParent();
                Path workerFile = Path.of("worker-api-key.txt");
                return parent == null ? workerFile : parent.resolve(workerFile);
            }
            return defaultApiKeyFile(targetKind);
        }
    }

    static final class ScenarioCredentialBootstrapper {
        private final ScenarioCredentialBootstrapOptions options;
        private final ObjectMapper objectMapper;
        private final HttpClient httpClient;

        ScenarioCredentialBootstrapper(ScenarioCredentialBootstrapOptions options,
                                       ObjectMapper objectMapper,
                                       HttpClient httpClient) {
            this.options = Objects.requireNonNull(options, "options is required");
            this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient is required");
        }

        Path prepare() throws IOException, InterruptedException {
            if (options.kind() == CredentialKind.ENV) {
                prepareEnvironment();
                return ScenarioCredentialBootstrapOptions.defaultApiKeyFile(CredentialKind.TASK)
                        .toAbsolutePath()
                        .normalize();
            }
            return prepareCredential(options, null);
        }

        List<Path> prepareEnvironment() throws IOException, InterruptedException {
            OperatorAuthContext auth = authenticateOperator();
            syncScenarioCatalog(auth);
            syncScenarioRules(auth);
            verifyScenarioCatalog();
            Path taskKey = prepareCredential(options.forKind(CredentialKind.TASK), auth);
            prepareWorkerCredentials(auth);
            return List.of(taskKey);
        }

        private Path prepareCredential(ScenarioCredentialBootstrapOptions credentialOptions,
                                       OperatorAuthContext auth) throws IOException, InterruptedException {
            if (Files.exists(credentialOptions.apiKeyFile())) {
                String cachedKey = Files.readString(credentialOptions.apiKeyFile(), StandardCharsets.UTF_8).trim();
                if (cachedKey.isBlank()) {
                    throw new IllegalStateException(credentialOptions.kind().label() + " API key cache is blank: "
                            + credentialOptions.apiKeyFile());
                }
                if (validateCachedKey(credentialOptions, cachedKey)) {
                    return credentialOptions.apiKeyFile();
                }
                if (!credentialOptions.refreshStaleCache()) {
                    throw new IllegalStateException("Cached " + credentialOptions.kind().label()
                            + " API key is invalid in the target server. Delete or refresh "
                            + credentialOptions.apiKeyFile());
                }
            } else if (!credentialOptions.createIfMissing()) {
                throw new IllegalStateException(credentialOptions.kind().label()
                        + " API key cache file is missing and creation is disabled: " + credentialOptions.apiKeyFile());
            }

            OperatorAuthContext resolvedAuth = auth == null ? authenticateOperator() : auth;
            String rawSecret = createApiKeyWithLocalPrincipalFallback(credentialOptions, resolvedAuth);
            Path parent = credentialOptions.apiKeyFile().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(credentialOptions.apiKeyFile(), rawSecret + System.lineSeparator(), StandardCharsets.UTF_8);
            return credentialOptions.apiKeyFile();
        }

        private String createApiKeyWithLocalPrincipalFallback(ScenarioCredentialBootstrapOptions credentialOptions,
                                                             OperatorAuthContext auth)
                throws IOException, InterruptedException {
            try {
                return createApiKey(credentialOptions, auth);
            } catch (IllegalStateException e) {
                if (!credentialOptions.refreshStaleCache() || !isPrincipalAlreadyExists(e)) {
                    throw e;
                }
                String replacementPrincipalId = credentialOptions.principalId()
                        + "-" + Long.toUnsignedString(System.currentTimeMillis(), 36);
                System.out.printf("[java-scenario-env-bootstrap] principal %s already has an API-key credential; "
                                + "creating local replacement principal %s%n",
                        credentialOptions.principalId(), replacementPrincipalId);
                return createApiKey(credentialOptions.withPrincipalId(replacementPrincipalId), auth);
            }
        }

        private static boolean isPrincipalAlreadyExists(IllegalStateException e) {
            String message = e.getMessage();
            return message != null && message.contains("principal already has an API key credential");
        }

        private boolean validateCachedKey(ScenarioCredentialBootstrapOptions credentialOptions,
                                          String apiKey) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/api-keys:current"))
                    .timeout(options.requestTimeout())
                    .header("X-Mass-Api-Key", apiKey)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return false;
            }
            JsonNode body = objectMapper.readTree(response.body());
            if (body.path("code").asInt(response.statusCode()) >= 400) {
                return false;
            }
            JsonNode permissions = body.path("data").path("permissions");
            if (!permissions.isArray()) {
                return false;
            }
            for (String permission : credentialOptions.permissions()) {
                if (!containsText(permissions, permission)) {
                    return false;
                }
            }
            JsonNode data = body.path("data");
            return containsAll(data.path("projectScopes"), credentialOptions.projectScopes())
                    && containsAll(data.path("eventScopes"), credentialOptions.eventScopes());
        }

        private OperatorAuthContext authenticateOperator() throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/auth/config"))
                    .timeout(options.requestTimeout())
                    .GET()
                    .build();
            JsonNode response = sendJson(request, "operator auth config");
            String authMode = response.path("data").path("authMode").asText("");
            if ("session".equalsIgnoreCase(authMode)) {
                return login();
            }
            if ("dev-header".equalsIgnoreCase(authMode)) {
                return new OperatorAuthContext("dev-header", null);
            }
            throw new IllegalStateException("unsupported operator auth mode for scenario initialization: " + authMode);
        }

        private OperatorAuthContext login() throws IOException, InterruptedException {
            String body = objectMapper.writeValueAsString(Map.of(
                    "userId", options.operatorUser(),
                    "password", options.operatorPassword()
            ));
            HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                    .timeout(options.requestTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            JsonNode response = sendJson(request, "operator login");
            String csrfToken = response.path("data").path("csrfToken").asText("");
            if (csrfToken.isBlank()) {
                throw new IllegalStateException("operator login did not return csrfToken");
            }
            return new OperatorAuthContext("session", csrfToken);
        }

        private String createApiKey(ScenarioCredentialBootstrapOptions credentialOptions,
                                    OperatorAuthContext auth) throws IOException, InterruptedException {
            String body = objectMapper.writeValueAsString(apiKeyRequestBody(
                    credentialOptions,
                    null,
                    Map.of(
                            "source", "scenario-credential-bootstrap",
                            "credentialKind", credentialOptions.kind().label(),
                            "local", "true"
                    )
            ));
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri("/api/v1/api-keys"))
                    .timeout(options.requestTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            applyOperatorAuth(builder, auth);
            HttpRequest request = builder.build();
            JsonNode response = sendJson(request, "API-key creation");
            String rawSecret = response.path("data").path("rawSecret").asText("");
            if (rawSecret.isBlank()) {
                throw new IllegalStateException("API-key creation did not return a rawSecret");
            }
            return rawSecret;
        }

        private void prepareWorkerCredentials(OperatorAuthContext auth) throws IOException, InterruptedException {
            List<WorkerScenarioSpec> workers = ScenarioFiles.load(
                    resolveManifestPath(ScenarioCredentialBootstrapOptions.DEFAULT_SCENARIO_DIR),
                    objectMapper
            ).workerSpecs();
            int createdOrReady = 0;
            for (WorkerScenarioSpec worker : workers) {
                prepareWorkerCredential(worker, auth);
                createdOrReady++;
            }
            System.out.printf("[java-scenario-env-bootstrap] worker credentials ready count=%d%n", createdOrReady);
        }

        private void prepareWorkerCredential(WorkerScenarioSpec worker,
                                             OperatorAuthContext auth) throws IOException, InterruptedException {
            String workerId = requireNonBlank(worker.workerId(), "workerId");
            String workerKey = requireNonBlank(worker.workerKey(), "workerKey");
            CurrentApiKey current = currentApiKey(workerKey);
            if (current != null && current.workerReadyFor(workerId)) {
                return;
            }
            if (current != null && options.refreshStaleCache()) {
                System.out.printf("[java-scenario-env-bootstrap] worker credential %s is active but not bound to "
                                + "workerId %s; revoking stale local credential %s before re-creating it%n",
                        current.principalId(), workerId, current.keyId());
                revokeApiKey(current.keyId(), auth, "scenario worker credential rebind for " + workerId);
            } else if (current != null) {
                throw new IllegalStateException("Worker credential " + current.principalId()
                        + " is active but is not bound to workerId " + workerId
                        + "; rerun without --no-refresh-stale-cache to rotate the local credential");
            }
            ScenarioCredentialBootstrapOptions workerOptions = options.forKind(CredentialKind.WORKER)
                    .withPrincipalId("scenario-worker-" + workerId);
            try {
                createApiKey(workerOptions, auth, workerKey, workerAttributes(workerId));
            } catch (IllegalStateException e) {
                if (!isPrincipalAlreadyExists(e)) {
                    throw e;
                }
                String replacementPrincipalId = "scenario-worker-" + workerId
                        + "-" + Long.toUnsignedString(System.currentTimeMillis(), 36);
                System.out.printf("[java-scenario-env-bootstrap] worker principal %s already has an API-key "
                                + "credential; creating local replacement principal %s%n",
                        workerOptions.principalId(), replacementPrincipalId);
                createApiKey(workerOptions.withPrincipalId(replacementPrincipalId), auth, workerKey,
                        workerAttributes(workerId));
            }
        }

        private CurrentApiKey currentApiKey(String apiKey) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/api-keys:current"))
                    .timeout(options.requestTimeout())
                    .header("X-Mass-Api-Key", apiKey)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            JsonNode body = objectMapper.readTree(response.body());
            if (body.path("code").asInt(response.statusCode()) >= 400) {
                return null;
            }
            JsonNode data = body.path("data");
            String keyId = data.path("credential").path("keyId").asText("");
            if (keyId.isBlank()) {
                return null;
            }
            return new CurrentApiKey(
                    keyId,
                    data.path("principalId").asText(""),
                    data.path("permissions"),
                    data.path("attributes").path("workerId").asText("")
            );
        }

        private void revokeApiKey(String keyId,
                                  OperatorAuthContext auth,
                                  String reason) throws IOException, InterruptedException {
            String body = objectMapper.writeValueAsString(Map.of("reason", reason));
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri("/api/v1/api-keys/" + encodePathSegment(keyId) + ":revoke"))
                    .timeout(options.requestTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            applyOperatorAuth(builder, auth);
            sendJson(builder.build(), "API-key revoke");
        }

        private String createApiKey(ScenarioCredentialBootstrapOptions credentialOptions,
                                    OperatorAuthContext auth,
                                    String rawSecret,
                                    Map<String, String> attributes) throws IOException, InterruptedException {
            String body = objectMapper.writeValueAsString(apiKeyRequestBody(credentialOptions, rawSecret, attributes));
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri("/api/v1/api-keys"))
                    .timeout(options.requestTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            applyOperatorAuth(builder, auth);
            HttpRequest request = builder.build();
            JsonNode response = sendJson(request, "API-key creation");
            String createdRawSecret = response.path("data").path("rawSecret").asText("");
            if (createdRawSecret.isBlank()) {
                throw new IllegalStateException("API-key creation did not return a rawSecret");
            }
            return createdRawSecret;
        }

        private Map<String, Object> apiKeyRequestBody(ScenarioCredentialBootstrapOptions credentialOptions,
                                                      String rawSecret,
                                                      Map<String, String> attributes) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("principalId", credentialOptions.principalId());
            body.put("createdForUserId", credentialOptions.createdForUserId());
            body.put("projectScopes", credentialOptions.projectScopes());
            body.put("eventScopes", credentialOptions.eventScopes());
            body.put("permissions", credentialOptions.permissions());
            body.put("attributes", attributes);
            if (rawSecret != null && !rawSecret.isBlank()) {
                body.put("rawSecret", rawSecret);
            }
            return body;
        }

        private Map<String, String> workerAttributes(String workerId) {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("source", "scenario-credential-bootstrap");
            attributes.put("credentialKind", "worker");
            attributes.put("local", "true");
            attributes.put("workerId", workerId);
            return attributes;
        }

        private void syncScenarioCatalog(OperatorAuthContext auth) throws IOException, InterruptedException {
            syncManifest(
                    ScenarioCredentialBootstrapOptions.DEFAULT_CATALOG_MANIFEST,
                    "/api/v1/control-plane/catalog:sync",
                    "scenario catalog sync",
                    auth
            );
        }

        private void syncScenarioRules(OperatorAuthContext auth) throws IOException, InterruptedException {
            syncManifest(
                    ScenarioCredentialBootstrapOptions.DEFAULT_RULES_MANIFEST,
                    "/api/v1/control-plane/rules:sync",
                    "scenario rules sync",
                    auth
            );
        }

        private void syncManifest(Path manifest,
                                  String path,
                                  String operation,
                                  OperatorAuthContext auth) throws IOException, InterruptedException {
            Path normalized = resolveManifestPath(manifest);
            if (!Files.exists(normalized)) {
                throw new IllegalStateException(operation + " manifest is missing: " + normalized);
            }
            String body = Files.readString(normalized, StandardCharsets.UTF_8);
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                    .timeout(options.requestTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            applyOperatorAuth(builder, auth);
            sendJson(builder.build(), operation);
        }

        private Path resolveManifestPath(Path manifest) {
            Path normalized = manifest.toAbsolutePath().normalize();
            if (Files.exists(normalized) || manifest.isAbsolute()) {
                return normalized;
            }
            Path repoRelativeFromModule = Path.of("..", "..").resolve(manifest).toAbsolutePath().normalize();
            return Files.exists(repoRelativeFromModule) ? repoRelativeFromModule : normalized;
        }

        private void applyOperatorAuth(HttpRequest.Builder builder, OperatorAuthContext auth) {
            if (auth == null) {
                return;
            }
            if (auth.session()) {
                builder.header("X-Mass-Csrf-Token", auth.csrfToken());
                return;
            }
            if (auth.devHeader()) {
                builder.header("X-Mass-User-Mode", "admin");
            }
        }

        private void verifyScenarioCatalog() throws IOException, InterruptedException {
            verifyScenarioEvent("crawler.fetch-page", "crawlerApp");
            verifyScenarioEvent("stock.quote.fetch", "crawlerApp");
            verifyScenarioEvent("probe.phone.metadata", "deviceProbe");
        }

        private void verifyScenarioEvent(String eventCode, String projectCode) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/catalog/events/" + encodePathSegment(eventCode)))
                    .timeout(options.requestTimeout())
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                throw new IllegalStateException("Scenario catalog is not initialized: missing event " + eventCode
                        + ". Re-run ScenarioCredentialBootstrapMain in env mode to initialize the local scenario "
                        + "through server control-plane APIs.");
            }
            JsonNode body = objectMapper.readTree(response.body());
            int code = body.path("code").asInt(response.statusCode());
            if (response.statusCode() >= 400 || code >= 400) {
                throw new IllegalStateException("Scenario catalog check failed HTTP " + response.statusCode()
                        + ": " + body.path("msg").asText(response.body()));
            }
            JsonNode projectCodes = body.path("data").path("projectCodes");
            if (!containsText(projectCodes, projectCode)) {
                throw new IllegalStateException("Scenario catalog event " + eventCode
                        + " is not bound to project " + projectCode
                        + ". Re-run ScenarioCredentialBootstrapMain in env mode.");
            }
        }

        private JsonNode sendJson(HttpRequest request, String operation) throws IOException, InterruptedException {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = objectMapper.readTree(response.body());
            int code = body.path("code").asInt(response.statusCode());
            if (response.statusCode() >= 400 || code >= 400) {
                String message = body.path("msg").asText(response.body());
                throw new IllegalStateException(operation + " failed HTTP " + response.statusCode()
                        + ": " + message);
            }
            return body;
        }

        private static boolean containsText(JsonNode array, String expected) {
            if (!array.isArray()) {
                return false;
            }
            for (JsonNode item : array) {
                if (expected.equals(item.asText())) {
                    return true;
                }
            }
            return false;
        }

        private static boolean containsAll(JsonNode array, List<String> expectedValues) {
            if (expectedValues == null || expectedValues.isEmpty()) {
                return true;
            }
            if (containsText(array, "*")) {
                return true;
            }
            for (String expected : expectedValues) {
                if (!containsText(array, expected)) {
                    return false;
                }
            }
            return true;
        }

        private static String encodePathSegment(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
        }

        private URI uri(String path) {
            return URI.create(options.baseUrl() + path);
        }

        private static String requireNonBlank(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return value.trim();
        }

        private record CurrentApiKey(String keyId,
                                     String principalId,
                                     JsonNode permissions,
                                     String workerId) {
            boolean workerReadyFor(String expectedWorkerId) {
                return containsText(permissions, "worker:poll") && expectedWorkerId.equals(workerId);
            }
        }
    }

    record OperatorAuthContext(String mode, String csrfToken) {
        boolean session() {
            return "session".equalsIgnoreCase(mode);
        }

        boolean devHeader() {
            return "dev-header".equalsIgnoreCase(mode);
        }
    }

    enum CredentialKind {
        ENV("env", List.of()),
        TASK("task", List.of("task:create", "task:edit", "task:view")),
        WORKER("worker", List.of("worker:poll"));

        private final String label;
        private final List<String> permissions;

        CredentialKind(String label, List<String> permissions) {
            this.label = label;
            this.permissions = permissions;
        }

        String label() {
            return label;
        }

        List<String> permissions() {
            return permissions;
        }

        static CredentialKind parse(String value) {
            if (value == null || value.isBlank()) {
                return TASK;
            }
            return switch (value.trim().toLowerCase()) {
                case "env" -> ENV;
                case "task" -> TASK;
                case "worker" -> WORKER;
                default -> throw new IllegalArgumentException("--kind must be env, task, or worker: " + value);
            };
        }
    }
}
