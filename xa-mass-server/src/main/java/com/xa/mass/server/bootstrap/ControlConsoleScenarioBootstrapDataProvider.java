package com.xa.mass.server.bootstrap;

import com.xa.mass.sdk.MassBootstrapDataProvider;
import com.xa.mass.sdk.MassRuntimeControl;
import com.xa.mass.sdk.WorkerClientOperations;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.authz.TaskOwnershipSupport;
import com.xa.mass.sdk.model.MassTaskItemBatchAppendRequest;
import com.xa.mass.sdk.model.MassTaskShellCreateRequest;
import com.xa.mass.sdk.model.AdapterNodeRegistration;
import com.xa.mass.sdk.model.NodeGroupBindingRegistration;
import com.xa.mass.sdk.model.TaskExecutionOptions;
import com.xa.mass.sdk.model.TaskShellSnapshot;
import com.xa.mass.sdk.model.WorkerEventBinding;
import com.xa.mass.sdk.model.WorkerGroupDeclaration;
import com.xa.mass.sdk.model.WorkerRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Dev-only generated bootstrap data for the backend-hosted control-console scenario.
 *
 * <p>The generated shape intentionally stays on SDK-native registration,
 * worker client presence, and shell/item APIs rather than test-only fixture
 * models. Public provider URLs are item payload data only; this bootstrap does
 * not call public internet services.
 */
public final class ControlConsoleScenarioBootstrapDataProvider implements MassBootstrapDataProvider {

    private static final Logger log = LoggerFactory.getLogger(ControlConsoleScenarioBootstrapDataProvider.class);

    public static final String PROFILE_DEV_DEMO = "dev-demo";
    public static final String PROFILE_LOCAL_ONLY = "local-only";

    private static final String POLLING_ADAPTER_NODE_ID = "control-console-polling";
    private static final String WEBSOCKET_ADAPTER_NODE_ID = "control-console-websocket";
    private static final List<String> REGIONS = List.of("us", "gb", "de", "fr", "sg", "jp");
    private static final List<ProbeTaskScenario> SCENARIO_CYCLE = List.of(
            new ProbeTaskScenario("publicProbe", "probe.url.dns", "dns-url-inspector", true),
            new ProbeTaskScenario("publicProbe", "probe.http.status", "public-probe-http", true),
            new ProbeTaskScenario("publicProbe", "probe.weather.current", "public-probe-http", false),
            new ProbeTaskScenario("publicProbe", "probe.fx.latest", "public-probe-http", false),
            new ProbeTaskScenario("deviceProbe", "probe.phone.metadata", "phone-device-probe", true),
            new ProbeTaskScenario("deviceProbe", "probe.phone.metadata", "phone-metadata-probe", true),
            new ProbeTaskScenario("dataQualityProbe", "probe.csv.validate", "local-json-validator", true),
            new ProbeTaskScenario("dataQualityProbe", "probe.json.schema", "local-json-validator", true),
            new ProbeTaskScenario("dataQualityProbe", "probe.market.daily-csv", "market-csv-parser", false),
            new ProbeTaskScenario("publicProbe", "probe.ip.geo", "public-probe-http", false)
    );
    private static final List<String> FINGERPRINT_PROFILES = List.of(
            "fp-android-sg-a",
            "fp-android-sg-b",
            "fp-android-sg-c",
            "fp-android-sg-d",
            "fp-android-sg-e",
            "fp-android-sg-f",
            "fp-android-sg-g",
            "fp-android-sg-h",
            "fp-android-sg-i",
            "fp-android-sg-j"
    );

    private final String profile;
    private final int workerCount;
    private final int taskCount;
    private final int itemsPerTask;
    private final int batchSize;
    private final int defaultMaxRetryCount;
    private final boolean autoApproveTasks;

    public ControlConsoleScenarioBootstrapDataProvider(String profile,
                                                       int workerCount,
                                                       int taskCount,
                                                       int itemsPerTask,
                                                       int batchSize,
                                                       int defaultMaxRetryCount,
                                                       boolean autoApproveTasks) {
        this.profile = normalizeProfile(profile);
        this.workerCount = Math.max(100, workerCount);
        this.taskCount = Math.max(1, taskCount);
        this.itemsPerTask = Math.max(1, itemsPerTask);
        this.batchSize = Math.max(1, batchSize);
        this.defaultMaxRetryCount = Math.max(0, defaultMaxRetryCount);
        this.autoApproveTasks = autoApproveTasks;
    }

    @Override
    public void loadInto(MassRuntimeControl runtime) {
        Objects.requireNonNull(runtime, "runtime");
        log.info(
                "Generating control-console scenario data [profile={}, workers={}, tasks={}, itemsPerTask={}, batchSize={}]",
                profile,
                workerCount,
                taskCount,
                itemsPerTask,
                batchSize
        );
        registerTopologyAndWorkers(runtime);
        createTasks(runtime);
        log.info(
                "Control-console scenario data generated [profile={}, workers={}, tasks={}, totalItems={}]",
                profile,
                workerCount,
                taskCount,
                (long) taskCount * itemsPerTask
        );
    }

    private void registerTopologyAndWorkers(MassRuntimeControl runtime) {
        runtime.registerAdapterNode(AdapterNodeRegistration.builder()
                .adapterNodeId(POLLING_ADAPTER_NODE_ID)
                .adapterType("polling")
                .adapterVersion("control-console-1")
                .endpointId("control-console-polling-endpoint")
                .attributes(Map.of("scenario", "control-console", "transport", "polling"))
                .build());
        runtime.registerAdapterNode(AdapterNodeRegistration.builder()
                .adapterNodeId(WEBSOCKET_ADAPTER_NODE_ID)
                .adapterType("websocket")
                .adapterVersion("control-console-1")
                .endpointId("control-console-websocket-endpoint")
                .attributes(Map.of("scenario", "control-console", "transport", "websocket"))
                .build());

        declareGroup(runtime, "public-probe-http", List.of(binding("probe.weather.current", "publicProbe"),
                binding("probe.fx.latest", "publicProbe"),
                binding("probe.crypto.price", "publicProbe"),
                binding("probe.ip.geo", "publicProbe"),
                binding("probe.http.status", "publicProbe")),
                Map.of("executionProfile", "public-http", "category", "network"), 4,
                POLLING_ADAPTER_NODE_ID, WEBSOCKET_ADAPTER_NODE_ID);
        declareGroup(runtime, "public-probe-browser", List.of(binding("probe.http.status", "publicProbe")),
                Map.of("executionProfile", "browser", "category", "network"), 2,
                WEBSOCKET_ADAPTER_NODE_ID);
        declareGroup(runtime, "dns-url-inspector", List.of(binding("probe.url.dns", "publicProbe")),
                Map.of("executionProfile", "dns-url", "category", "network"), 2,
                POLLING_ADAPTER_NODE_ID, WEBSOCKET_ADAPTER_NODE_ID);
        declareGroup(runtime, "market-csv-parser", List.of(binding("probe.market.daily-csv", "dataQualityProbe")),
                Map.of("executionProfile", "csv-market", "category", "parser"), 2,
                POLLING_ADAPTER_NODE_ID);
        declareGroup(runtime, "local-json-validator", List.of(
                binding("probe.csv.validate", "dataQualityProbe"),
                binding("probe.json.schema", "dataQualityProbe")),
                Map.of("executionProfile", "local-validator", "category", "validator"), 2,
                POLLING_ADAPTER_NODE_ID);
        declareGroup(runtime, "phone-metadata-probe", List.of(binding("probe.phone.metadata", "deviceProbe")),
                Map.of("executionProfile", "phone-metadata", "category", "local-tool"), 2,
                POLLING_ADAPTER_NODE_ID);
        declareGroup(runtime, "phone-device-probe", List.of(binding("probe.phone.metadata", "deviceProbe")),
                Map.of("executionProfile", "phone-device", "country", "SG", "category", "device"), 2,
                POLLING_ADAPTER_NODE_ID, WEBSOCKET_ADAPTER_NODE_ID);

        registerWorkers(runtime);
    }

    private void registerWorkers(MassRuntimeControl runtime) {
        for (int i = 0; i < 60; i++) {
            String region = REGIONS.get(i % REGIONS.size());
            registerWorker(runtime, String.format(Locale.ROOT, "public-probe-http-poll-%s-%03d", region, i + 1),
                    "public-probe-http", POLLING_ADAPTER_NODE_ID, "polling",
                    Map.of("region", region, "category", "network", "capacity", "standard"));
        }
        for (int i = 0; i < 20; i++) {
            String region = REGIONS.get(i % REGIONS.size());
            registerWorker(runtime, String.format(Locale.ROOT, "public-probe-http-ws-%s-%03d", region, i + 1),
                    "public-probe-http", WEBSOCKET_ADAPTER_NODE_ID, "realtime",
                    Map.of("region", region, "category", "network", "capacity", "interactive"));
        }
        for (int i = 0; i < 10; i++) {
            registerWorker(runtime, String.format(Locale.ROOT, "dns-url-inspector-poll-%03d", i + 1),
                    "dns-url-inspector", POLLING_ADAPTER_NODE_ID, "polling",
                    Map.of("category", "dns", "resolverProfile", i % 2 == 0 ? "system" : "fixture"));
        }
        for (int i = 0; i < 5; i++) {
            registerWorker(runtime, String.format(Locale.ROOT, "dns-url-inspector-ws-%03d", i + 1),
                    "dns-url-inspector", WEBSOCKET_ADAPTER_NODE_ID, "realtime",
                    Map.of("category", "dns", "resolverProfile", "interactive"));
        }
        for (int i = 0; i < 10; i++) {
            registerWorker(runtime, String.format(Locale.ROOT, "market-csv-parser-poll-%03d", i + 1),
                    "market-csv-parser", POLLING_ADAPTER_NODE_ID, "polling",
                    Map.of("category", "parser", "format", "csv"));
        }
        for (int i = 0; i < 10; i++) {
            registerWorker(runtime, String.format(Locale.ROOT, "local-json-validator-poll-%03d", i + 1),
                    "local-json-validator", POLLING_ADAPTER_NODE_ID, "polling",
                    Map.of("category", "validator", "format", i % 2 == 0 ? "json" : "csv"));
        }
        for (int i = 0; i < 10; i++) {
            registerWorker(runtime, String.format(Locale.ROOT, "phone-metadata-probe-poll-%03d", i + 1),
                    "phone-metadata-probe", POLLING_ADAPTER_NODE_ID, "polling",
                    Map.of("category", "phone", "tool", "metadata"));
        }
        for (int i = 0; i < 20; i++) {
            registerWorker(runtime, String.format(Locale.ROOT, "phone-device-probe-poll-sg-%03d", i + 1),
                    "phone-device-probe", POLLING_ADAPTER_NODE_ID, "polling",
                    deviceAttributes(i, "polling"));
        }
        for (int i = 0; i < 10; i++) {
            registerWorker(runtime, String.format(Locale.ROOT, "phone-device-probe-ws-sg-%03d", i + 1),
                    "phone-device-probe", WEBSOCKET_ADAPTER_NODE_ID, "realtime",
                    deviceAttributes(i + 20, "realtime"));
        }
    }

    private void createTasks(MassRuntimeControl runtime) {
        for (int taskIndex = 0; taskIndex < taskCount; taskIndex++) {
            ProbeTaskScenario scenario = scenarioFor(taskIndex);
            TaskExecutionOptions execution = executionOptionsFor(taskIndex);
            PrincipalContext owner = ownerForProject(scenario.projectCode());

            TaskShellSnapshot task = runtime.createTaskShell(TaskOwnershipSupport.stamp(
                    MassTaskShellCreateRequest.builder()
                            .userId(owner.getUserId())
                            .project(scenario.projectCode())
                            .contract("BATCH")
                            .sourceRef(String.format(Locale.ROOT, "control-console-%s-%02d",
                                    scenario.eventCode(), taskIndex + 1))
                            .sharedConfig(buildSharedConfig(taskIndex, scenario))
                            .executionSpec(execution)
                            .build(),
                    owner
            ));

            runtime.appendTaskItems(task.getTaskId(), MassTaskItemBatchAppendRequest.builder()
                    .eventCode(scenario.eventCode())
                    .items(buildItems(taskIndex, scenario))
                    .build());
            applyLifecycle(runtime, task.getTaskId());
        }
    }

    private void applyLifecycle(MassRuntimeControl runtime, String taskId) {
        runtime.sealTask(taskId);
        if (autoApproveTasks) {
            runtime.approveTask(taskId);
        }
    }

    private Map<String, Object> buildSharedConfig(int taskIndex,
                                                  ProbeTaskScenario scenario) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("scenario", "control-console-realistic");
        config.put("scenarioProfile", profile);
        config.put("taskIndex", taskIndex + 1);
        config.put("eventCode", scenario.eventCode());
        config.put("workerGroupId", scenario.workerGroupId());
        config.put("textContent", "control console probe task " + (taskIndex + 1));
        if ("phone-device-probe".equals(scenario.workerGroupId())) {
            config.put("requiredFingerprintProfile", FINGERPRINT_PROFILES.get(taskIndex % FINGERPRINT_PROFILES.size()));
        }
        return Map.copyOf(config);
    }

    private TaskExecutionOptions executionOptionsFor(int taskIndex) {
        TaskExecutionOptions execution = new TaskExecutionOptions();
        execution.setBatchSize(taskIndex % 3 == 2 ? Math.max(5, Math.min(batchSize, 10)) : batchSize);
        execution.setDefaultMaxRetryCount(defaultMaxRetryCount);
        execution.setWorkloadClass("BULK");
        execution.setMaxRuntimeSeconds(1800);
        execution.setProfile("STANDARD");
        return execution;
    }

    private List<Object> buildItems(int taskIndex, ProbeTaskScenario scenario) {
        List<Object> items = new ArrayList<>(itemsPerTask);
        for (int itemIndex = 0; itemIndex < itemsPerTask; itemIndex++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("caseId", String.format(Locale.ROOT, "%s-%02d-%05d",
                    scenario.eventCode(), taskIndex + 1, itemIndex + 1));
            item.put("taskIndex", taskIndex + 1);
            item.put("sequence", itemIndex + 1);
            item.put("project", scenario.projectCode());
            item.put("eventCode", scenario.eventCode());
            item.put("workerGroupId", scenario.workerGroupId());
            item.put("sleepMs", sleepMs(itemIndex));
            item.put("timeoutMs", timeoutMs(itemIndex));
            item.put("expectedOutcome", expectedOutcome(scenario, itemIndex));
            item.put("traceLabel", traceLabel(scenario, itemIndex));
            applyEventPayload(item, scenario, itemIndex);
            items.add(item);
        }
        return items;
    }

    private void declareGroup(MassRuntimeControl runtime,
                              String groupId,
                              List<WorkerEventBinding> eventBindings,
                              Map<String, String> defaultAttributes,
                              int defaultMaxConcurrentWork,
                              String... adapterNodeIds) {
        runtime.declareWorkerGroup(WorkerGroupDeclaration.builder()
                .groupId(groupId)
                .eventBindings(eventBindings)
                .defaultAttributes(defaultAttributes)
                .defaultMaxConcurrentWork(defaultMaxConcurrentWork)
                .build());
        for (String adapterNodeId : adapterNodeIds) {
            runtime.bindNodeGroup(NodeGroupBindingRegistration.builder()
                    .adapterNodeId(adapterNodeId)
                    .workerGroupId(groupId)
                    .pluginVersion("control-console-1")
                    .deploymentVersion(profile)
                    .attributes(Map.of("scenario", "control-console", "profile", profile))
                    .build());
        }
    }

    private WorkerEventBinding binding(String eventCode, String projectCode) {
        return WorkerEventBinding.builder()
                .eventCode(eventCode)
                .projectCodes(List.of(projectCode))
                .build();
    }

    private void registerWorker(MassRuntimeControl runtime,
                                String workerId,
                                String workerGroupId,
                                String adapterNodeId,
                                String transportHint,
                                Map<String, String> attributes) {
        runtime.registerWorker(WorkerRegistration.builder()
                .workerId(workerId)
                .adapterNodeId(adapterNodeId)
                .workerGroupId(workerGroupId)
                .adapterId("polling".equals(transportHint) ? "polling" : "websocket")
                .transportHint(transportHint)
                .attributes(attributes)
                .build());
        if ("polling".equals(transportHint) && runtime instanceof WorkerClientOperations workerClient) {
            workerClient.workerOnline(workerId, "control-console scenario polling worker online");
        }
    }

    private Map<String, String> deviceAttributes(int index, String transportHint) {
        String profileId = FINGERPRINT_PROFILES.get(index % FINGERPRINT_PROFILES.size());
        String operator = index % 2 == 0 ? "52501" : "52505";
        String model = switch (index % 4) {
            case 0 -> "Pixel 7";
            case 1 -> "Galaxy S23";
            case 2 -> "Pixel 8";
            default -> "Galaxy S24";
        };
        return Map.of(
                "executionProfile", "phone-device",
                "country", "SG",
                "transportKind", transportHint,
                "fingerprintProfile", profileId,
                "fingerprintHash", "sha256:dev-" + profileId + "-" + String.format(Locale.ROOT, "%03d", index + 1),
                "deviceModel", model,
                "osVersion", "Android 14",
                "simOperatorMccMnc", operator,
                "networkOperatorMccMnc", operator,
                "riskTier", index % 3 == 0 ? "medium" : "low"
        );
    }

    private ProbeTaskScenario scenarioFor(int taskIndex) {
        List<ProbeTaskScenario> scenarios = PROFILE_LOCAL_ONLY.equals(profile)
                ? SCENARIO_CYCLE.stream().filter(ProbeTaskScenario::localOnlySafe).toList()
                : SCENARIO_CYCLE;
        return scenarios.get(taskIndex % scenarios.size());
    }

    private PrincipalContext ownerForProject(String project) {
        return switch (project) {
            case "deviceProbe" -> owner("device-probe-runner", "device-probe-user", "deviceProbe",
                    List.of("probe.phone.metadata"));
            case "dataQualityProbe" -> owner("data-quality-runner", "data-quality-user", "dataQualityProbe",
                    List.of("probe.market.daily-csv", "probe.csv.validate", "probe.json.schema"));
            default -> owner("public-probe-runner", "public-probe-user", "publicProbe",
                    List.of("probe.weather.current", "probe.fx.latest", "probe.crypto.price",
                            "probe.ip.geo", "probe.url.dns", "probe.http.status"));
        };
    }

    private PrincipalContext owner(String principalId, String userId, String project, List<String> eventScopes) {
        return PrincipalContext.builder()
                .principalId(principalId)
                .userId(userId)
                .projectScope(project)
                .permissions(List.of(PrincipalContext.TASK_CREATE_PERMISSION))
                .projectScopes(List.of(project))
                .eventScopes(eventScopes)
                .build();
    }

    private void applyEventPayload(Map<String, Object> item, ProbeTaskScenario scenario, int itemIndex) {
        switch (scenario.eventCode()) {
            case "probe.weather.current" -> {
                item.put("latitude", 22.5431);
                item.put("longitude", 114.0579);
                item.put("provider", "open-meteo");
                item.put("expected", Map.of("temperature_2m", "exists", "range", "-60..60"));
            }
            case "probe.fx.latest" -> {
                item.put("baseCurrency", "USD");
                item.put("quoteCurrencies", List.of("CNY", "EUR"));
                item.put("provider", "open.er-api.com");
            }
            case "probe.crypto.price" -> {
                item.put("assets", List.of("bitcoin", "ethereum"));
                item.put("vsCurrencies", List.of("usd", "cny"));
                item.put("provider", "coingecko");
            }
            case "probe.ip.geo" -> {
                item.put("ip", itemIndex % 2 == 0 ? "8.8.8.8" : "1.1.1.1");
                item.put("provider", "ipwho.is");
            }
            case "probe.http.status" -> {
                int status = itemIndex % 9 == 0 ? 503 : itemIndex % 7 == 0 ? 429 : 200;
                item.put("url", "https://httpbin.org/status/" + status);
                item.put("expectedStatus", status);
            }
            case "probe.url.dns" -> {
                if (itemIndex % 5 == 0) {
                    item.put("url", "https://does-not-exist.public-probe.invalid/");
                    item.put("expected", Map.of("dnsOutcome", "DNS_NXDOMAIN", "httpRequestSkipped", true));
                } else {
                    item.put("url", PROFILE_LOCAL_ONLY.equals(profile) ? "https://fixture.local.test/" : "https://example.com/");
                    item.put("expected", Map.of("hostname", "exists", "dnsOutcome", "RESOLVED"));
                }
            }
            case "probe.phone.metadata" -> {
                String requiredProfile = FINGERPRINT_PROFILES.get(itemIndex % FINGERPRINT_PROFILES.size());
                item.put("phoneNumber", itemIndex % 2 == 0 ? "+14155552671" : "+6581234567");
                item.put("defaultRegion", itemIndex % 2 == 0 ? "US" : "SG");
                item.put("requiredFingerprintProfile", requiredProfile);
                item.put("requiredNetworkOperatorMccMnc", itemIndex % 2 == 0 ? "52501" : "52505");
                item.put("expected", Map.of("e164", "exists", "possible", true));
            }
            case "probe.market.daily-csv" -> {
                item.put("symbol", itemIndex % 2 == 0 ? "aapl.us" : "%5Espx");
                item.put("provider", "stooq");
                item.put("requiredColumns", List.of("Date", "Open", "High", "Low", "Close", "Volume"));
            }
            case "probe.csv.validate" -> {
                item.put("fixtureName", itemIndex % 11 == 0 ? "orders-invalid-missing-total.csv" : "orders-valid.csv");
                item.put("requiredColumns", List.of("orderId", "country", "total"));
            }
            case "probe.json.schema" -> {
                item.put("fixtureName", itemIndex % 13 == 0 ? "profile-invalid.json" : "profile-valid.json");
                item.put("requiredFields", List.of("id", "name", "country"));
            }
            default -> item.put("payload", "control-console probe payload");
        }
    }

    private int sleepMs(int itemIndex) {
        return itemIndex % 20 == 0 ? 1500 + (itemIndex % 4) * 500 : 100 + (itemIndex % 7) * 75;
    }

    private int timeoutMs(int itemIndex) {
        return itemIndex % 20 == 0 ? 2000 : 3000 + (itemIndex % 5) * 500;
    }

    private String expectedOutcome(ProbeTaskScenario scenario, int itemIndex) {
        if ("probe.url.dns".equals(scenario.eventCode()) && itemIndex % 5 == 0) {
            return "DNS_NXDOMAIN";
        }
        if ("probe.http.status".equals(scenario.eventCode()) && itemIndex % 9 == 0) {
            return "HTTP_STATUS_UNEXPECTED";
        }
        if ("probe.http.status".equals(scenario.eventCode()) && itemIndex % 7 == 0) {
            return "HTTP_STATUS_UNEXPECTED";
        }
        if ("probe.csv.validate".equals(scenario.eventCode()) && itemIndex % 11 == 0) {
            return "CSV_INVALID";
        }
        if ("probe.json.schema".equals(scenario.eventCode()) && itemIndex % 13 == 0) {
            return "SCHEMA_INVALID";
        }
        return "SUCCESS";
    }

    private String traceLabel(ProbeTaskScenario scenario, int itemIndex) {
        String outcome = expectedOutcome(scenario, itemIndex);
        return scenario.eventCode().replace("probe.", "").replace('.', '-') + "-" + outcome.toLowerCase(Locale.ROOT);
    }

    private String normalizeProfile(String value) {
        if (value == null || value.isBlank()) {
            return PROFILE_DEV_DEMO;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return PROFILE_LOCAL_ONLY.equals(normalized) ? PROFILE_LOCAL_ONLY : PROFILE_DEV_DEMO;
    }

    public boolean isLocalOnlyProfile() {
        return PROFILE_LOCAL_ONLY.equals(profile);
    }

    public int configuredTaskCount() {
        return taskCount;
    }

    public int configuredItemsPerTask() {
        return itemsPerTask;
    }

    public Collection<String> fingerprintProfiles() {
        return FINGERPRINT_PROFILES;
    }

    private record ProbeTaskScenario(String projectCode,
                                     String eventCode,
                                     String workerGroupId,
                                     boolean localOnlySafe) {
    }
}
