package com.xa.mass.server.bootstrap;

import com.xa.mass.sdk.MassBootstrapDataProvider;
import com.xa.mass.sdk.MassRuntimeControl;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Dev-only generated bootstrap data for a richer startup demo shell.
 *
 * <p>The generated shape intentionally stays on SDK-native registration and
 * shell/item APIs rather than test-only fixture models.
 */
public final class DevDemoBootstrapDataProvider implements MassBootstrapDataProvider {

    private static final Logger log = LoggerFactory.getLogger(DevDemoBootstrapDataProvider.class);
    private static final List<String> DEMO_PROJECTS = List.of("demoApp", "demoOps");
    private static final List<DemoTaskScenario> SCENARIO_CYCLE = List.of(
            DemoTaskScenario.ACTIVE,
            DemoTaskScenario.ACTIVE_GB,
            DemoTaskScenario.ACTIVE_OPS,
            DemoTaskScenario.PENDING_REVIEW,
            DemoTaskScenario.PAUSED,
            DemoTaskScenario.BLOCKED
    );

    private final int workerCount;
    private final int taskCount;
    private final int itemsPerTask;
    private final int batchSize;
    private final int defaultMaxRetryCount;
    private final boolean autoApproveTasks;
    private final List<String> routingLanes;

    public DevDemoBootstrapDataProvider(int workerCount,
                                        int taskCount,
                                        int itemsPerTask,
                                        int batchSize,
                                        int defaultMaxRetryCount,
                                        boolean autoApproveTasks,
                                        List<String> routingLanes) {
        this.workerCount = Math.max(1, workerCount);
        this.taskCount = Math.max(1, taskCount);
        this.itemsPerTask = Math.max(1, itemsPerTask);
        this.batchSize = Math.max(1, batchSize);
        this.defaultMaxRetryCount = Math.max(0, defaultMaxRetryCount);
        this.autoApproveTasks = autoApproveTasks;
        this.routingLanes = normalizeRoutingLanes(routingLanes);
    }

    @Override
    public void loadInto(MassRuntimeControl runtime) {
        Objects.requireNonNull(runtime, "runtime");
        log.info(
                "Generating dev demo bootstrap data [workers={}, tasks={}, itemsPerTask={}, batchSize={}, lanes={}]",
                workerCount,
                taskCount,
                itemsPerTask,
                batchSize,
                routingLanes
        );
        registerWorkers(runtime);
        createTasks(runtime);
        log.info(
                "Dev demo bootstrap data generated [workers={}, tasks={}, totalItems={}]",
                workerCount,
                taskCount,
                (long) taskCount * itemsPerTask
        );
    }

    private void registerWorkers(MassRuntimeControl runtime) {
        String adapterNodeId = "dev-demo-websocket";
        runtime.registerAdapterNode(AdapterNodeRegistration.builder()
                .adapterNodeId(adapterNodeId)
                .adapterType("websocket")
                .endpointId("dev-demo")
                .attributes(Map.of("tier", "demo"))
                .build());
        for (String lane : routingLanes) {
            runtime.declareWorkerGroup(WorkerGroupDeclaration.builder()
                    .groupId(lane)
                    .eventBindings(eventBindingsForLane(lane))
                    .defaultAttributes(Map.of(
                            "tier", "demo",
                            "lane", lane,
                            "country", lane,
                            "pool", "demo-" + lane
                    ))
                    .build());
            runtime.bindNodeGroup(NodeGroupBindingRegistration.builder()
                    .adapterNodeId(adapterNodeId)
                    .workerGroupId(lane)
                    .attributes(Map.of("tier", "demo", "lane", lane))
                    .build());
        }
        for (int i = 0; i < workerCount; i++) {
            String lane = routingLanes.get(i % routingLanes.size());
            String workerId = String.format(Locale.ROOT, "demo-worker-%s-%02d", lane, i + 1);
            runtime.registerWorker(WorkerRegistration.builder()
                    .workerId(workerId)
                    .adapterNodeId(adapterNodeId)
                    .workerGroupId(lane)
                    .adapterId("websocket")
                    .transportHint("realtime")
                    .attributes(Map.of(
                            "tier", "demo",
                            "lane", lane,
                            "country", lane,
                            "pool", "demo-" + lane,
                            "routingTags", lane,
                            "capacity", "standard"
                    ))
                    .build());
        }
    }

    private void createTasks(MassRuntimeControl runtime) {
        for (int taskIndex = 0; taskIndex < taskCount; taskIndex++) {
            String project = projectFor(taskIndex);
            DemoTaskScenario scenario = scenarioFor(taskIndex);
            String lane = effectiveLane(taskIndex, scenario);
            String eventCode = eventCodeForLane(lane);
            TaskExecutionOptions execution = executionOptionsFor(scenario, taskIndex);
            PrincipalContext owner = ownerForProject(project);

            TaskShellSnapshot task = runtime.createTaskShell(TaskOwnershipSupport.stamp(
                    MassTaskShellCreateRequest.builder()
                            .userId(owner.getUserId())
                            .project(project)
                            .contract("BATCH")
                            .sourceRef(String.format(Locale.ROOT, "dev-demo-%s-%02d", lane, taskIndex + 1))
                            .sharedConfig(buildSharedConfig(taskIndex, lane, project, scenario))
                            .executionSpec(execution)
                            .build(),
                    owner
            ));

            runtime.appendTaskItems(task.getTaskId(), MassTaskItemBatchAppendRequest.builder()
                    .eventCode(eventCode)
                    .items(buildItems(taskIndex, lane, project, scenario))
                    .build());
            applyLifecycle(runtime, task.getTaskId(), scenario);
        }
    }

    private void applyLifecycle(MassRuntimeControl runtime, String taskId, DemoTaskScenario scenario) {
        runtime.sealTask(taskId);
        switch (scenario) {
            case ACTIVE, ACTIVE_GB, ACTIVE_OPS -> {
                if (autoApproveTasks) {
                    runtime.approveTask(taskId);
                }
            }
            case PAUSED -> {
                if (autoApproveTasks && runtime.approveTask(taskId)) {
                    runtime.pauseTask(taskId);
                }
            }
            case BLOCKED -> runtime.rejectTask(taskId);
            case PENDING_REVIEW -> {
                // Leave the task in NEW after sealing so the demo shell shows operator approval flow.
            }
        }
    }

    private Map<String, Object> buildSharedConfig(int taskIndex,
                                                  String lane,
                                                  String project,
                                                  DemoTaskScenario scenario) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("routingCode", lane);
        config.put("textContent", "dev demo bootstrap task " + (taskIndex + 1));
        config.put("demoSeed", taskIndex + 1);
        config.put("demoScenario", scenario.name().toLowerCase(Locale.ROOT));
        config.put("demoProject", project);
        config.put("priority", switch (scenario) {
            case ACTIVE, ACTIVE_GB -> "standard";
            case ACTIVE_OPS -> "ops";
            case PAUSED -> "deferred";
            case BLOCKED -> "blocked";
            case PENDING_REVIEW -> "review";
        });
        return Map.copyOf(config);
    }

    private TaskExecutionOptions executionOptionsFor(DemoTaskScenario scenario, int taskIndex) {
        TaskExecutionOptions execution = new TaskExecutionOptions();
        execution.setBatchSize(scenario == DemoTaskScenario.ACTIVE_OPS ? Math.max(batchSize / 2, 5) : batchSize);
        execution.setDefaultMaxRetryCount(defaultMaxRetryCount);
        execution.setWorkloadClass("BULK");
        execution.setMaxRuntimeSeconds(scenario == DemoTaskScenario.ACTIVE_OPS ? 900 : 3600);
        execution.setProfile(scenario == DemoTaskScenario.ACTIVE_OPS ? "LATENCY_SENSITIVE" : "STANDARD");
        if (taskIndex % 3 == 2 && scenario != DemoTaskScenario.ACTIVE_OPS) {
            execution.setBatchSize(Math.max(5, Math.min(batchSize, 10)));
        }
        return execution;
    }

    private List<Object> buildItems(int taskIndex, String lane, String project, DemoTaskScenario scenario) {
        List<Object> items = new ArrayList<>(itemsPerTask);
        for (int itemIndex = 0; itemIndex < itemsPerTask; itemIndex++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("target", String.format(Locale.ROOT, "%s-target-%05d", lane, itemIndex + 1));
            item.put("taskIndex", taskIndex + 1);
            item.put("sequence", itemIndex + 1);
            item.put("lane", lane);
            item.put("project", project);
            item.put("scenario", scenario.name().toLowerCase(Locale.ROOT));
            item.put("payload", "demo payload " + (taskIndex + 1) + "-" + (itemIndex + 1));
            items.add(item);
        }
        return items;
    }

    private List<WorkerEventBinding> eventBindingsForLane(String lane) {
        List<WorkerEventBinding> bindings = new ArrayList<>();
        bindings.add(WorkerEventBinding.builder()
                .eventCode("demo.dispatch")
                .projectCodes(DEMO_PROJECTS)
                .build());
        if ("gb".equalsIgnoreCase(lane)) {
            bindings.add(WorkerEventBinding.builder()
                    .eventCode("demo.dispatch.gb")
                    .projectCodes(DEMO_PROJECTS)
                    .build());
        }
        return List.copyOf(bindings);
    }

    private DemoTaskScenario scenarioFor(int taskIndex) {
        int scenarioIndex = (taskIndex / DEMO_PROJECTS.size()) % SCENARIO_CYCLE.size();
        return SCENARIO_CYCLE.get(scenarioIndex);
    }

    private String projectFor(int taskIndex) {
        return DEMO_PROJECTS.get(taskIndex % DEMO_PROJECTS.size());
    }

    private String effectiveLane(int taskIndex, DemoTaskScenario scenario) {
        if (scenario == DemoTaskScenario.ACTIVE_GB && routingLanes.contains("gb")) {
            return "gb";
        }
        return routingLanes.get(taskIndex % routingLanes.size());
    }

    private String eventCodeForLane(String lane) {
        return "gb".equalsIgnoreCase(lane) ? "demo.dispatch.gb" : "demo.dispatch";
    }

    private PrincipalContext ownerForProject(String project) {
        String normalizedProject = project == null ? "" : project.trim();
        if ("demoOps".equals(normalizedProject)) {
            return PrincipalContext.builder()
                    .principalId("demo-ops-submitter")
                    .userId("demo-ops-user")
                    .projectScope("demoOps")
                    .permissions(List.of(PrincipalContext.TASK_CREATE_PERMISSION))
                    .projectScopes(List.of("demoOps"))
                    .eventScopes(List.of("demo.dispatch", "demo.dispatch.gb"))
                    .build();
        }
        return PrincipalContext.builder()
                .principalId("demo-app-submitter")
                .userId("demo-app-user")
                .projectScope("demoApp")
                .permissions(List.of(PrincipalContext.TASK_CREATE_PERMISSION))
                .projectScopes(List.of("demoApp"))
                .eventScopes(List.of("demo.dispatch", "demo.dispatch.gb"))
                .build();
    }

    private static List<String> normalizeRoutingLanes(List<String> routingLanes) {
        if (routingLanes == null || routingLanes.isEmpty()) {
            return List.of("us", "gb", "de", "fr", "sg", "jp");
        }
        List<String> normalized = routingLanes.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        return normalized.isEmpty() ? List.of("us", "gb", "de", "fr", "sg", "jp") : normalized;
    }

    private enum DemoTaskScenario {
        ACTIVE,
        ACTIVE_GB,
        ACTIVE_OPS,
        PENDING_REVIEW,
        PAUSED,
        BLOCKED
    }
}
