package com.xa.mass.server.bootstrap;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.sdk.MassRuntimeControl;
import com.xa.mass.sdk.model.AdapterNodeRegistration;
import com.xa.mass.sdk.model.MassTaskItemBatchAppendRequest;
import com.xa.mass.sdk.model.MassTaskShellCreateRequest;
import com.xa.mass.sdk.model.NodeGroupBindingRegistration;
import com.xa.mass.sdk.model.TaskExecutionOptions;
import com.xa.mass.sdk.model.TaskShellSnapshot;
import com.xa.mass.sdk.model.WorkerEventBinding;
import com.xa.mass.sdk.model.WorkerGroupDeclaration;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.transport.WorkerTransportHints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Test/fixture runtime data loader.
 */
public class MockRuntimeDataLoader {

    private static final Logger logger = LoggerFactory.getLogger(MockRuntimeDataLoader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .registerModule(new JavaTimeModule());

    private final String workerConfigPath;
    private final String taskConfigPath;
    private final String ruleConfigPath;
    private final boolean loadWorkers;
    private final boolean loadTasks;
    private final boolean loadRules;

    public MockRuntimeDataLoader(String workerConfigPath,
                                 String taskConfigPath,
                                 String ruleConfigPath) {
        this(workerConfigPath, taskConfigPath, ruleConfigPath,
                true, true, true);
    }

    public MockRuntimeDataLoader(String workerConfigPath,
                                 String taskConfigPath,
                                 String ruleConfigPath,
                                 boolean loadWorkers,
                                 boolean loadTasks,
                                 boolean loadRules) {
        this.workerConfigPath = workerConfigPath;
        this.taskConfigPath = taskConfigPath;
        this.ruleConfigPath = ruleConfigPath;
        this.loadWorkers = loadWorkers;
        this.loadTasks = loadTasks;
        this.loadRules = loadRules;
    }

    public void loadInto(MassRuntimeControl runtime) {
        Objects.requireNonNull(runtime, "runtime");
        logger.info("Loading bootstrap data [workers={}, rules={}, tasks={}]",
                workerConfigPath, ruleConfigPath, taskConfigPath);
        if (loadWorkers) {
            loadWorkers(runtime);
        } else {
            logger.info("Worker bootstrap load disabled [path={}]", workerConfigPath);
        }
        if (loadRules) {
            loadRules(runtime);
        } else {
            logger.info("Rule bootstrap load disabled [path={}]", ruleConfigPath);
        }
        if (loadTasks) {
            loadTasks(runtime);
        } else {
            logger.info("Task bootstrap load disabled [path={}]", taskConfigPath);
        }
        logger.info("Runtime data load completed");
    }

    private void loadWorkers(MassRuntimeControl runtime) {
        WorkerFixture[] workers = readConfig(workerConfigPath, WorkerFixture[].class);
        if (workers == null) return;
        if (workers.length == 0) {
            logger.warn("Worker config loaded but produced 0 entries [path={}]", workerConfigPath);
            return;
        }
        List<WorkerFixture> normalizedWorkers = new ArrayList<>();
        for (WorkerFixture worker : workers) {
            if (worker == null || worker.getWorkerId() == null || worker.getWorkerId().isBlank()) {
                logger.warn("Skipping worker fixture because workerId is missing");
                continue;
            }
            normalizeWorker(worker);
            normalizedWorkers.add(worker);
        }
        declareWorkerGroups(runtime, normalizedWorkers);
        bindWorkerGroups(runtime, normalizedWorkers);
        int accepted = 0;
        for (WorkerFixture worker : normalizedWorkers) {
            runtime.registerWorker(toRegistration(worker));
            accepted++;
        }
        logger.info("Loaded {} workers via SDK registration [path={}]", accepted, workerConfigPath);
    }

    private void declareWorkerGroups(MassRuntimeControl runtime, List<WorkerFixture> workers) {
        Map<String, List<WorkerEventBinding>> bindingsByGroupId = new LinkedHashMap<>();
        for (WorkerFixture worker : workers) {
            String groupId = worker.getWorkerGroupId();
            if (groupId == null || groupId.isBlank()) {
                continue;
            }
            List<WorkerEventBinding> eventBindings = toEventBindings(worker);
            if (!eventBindings.isEmpty()) {
                bindingsByGroupId.computeIfAbsent(groupId, ignored -> new ArrayList<>()).addAll(eventBindings);
            }
        }
        for (Map.Entry<String, List<WorkerEventBinding>> entry : bindingsByGroupId.entrySet()) {
            runtime.declareWorkerGroup(WorkerGroupDeclaration.builder()
                    .groupId(entry.getKey())
                    .eventBindings(distinctBindings(entry.getValue()))
                    .build());
        }
    }

    private void bindWorkerGroups(MassRuntimeControl runtime, List<WorkerFixture> workers) {
        Map<String, List<String>> groupsByAdapterNode = new LinkedHashMap<>();
        for (WorkerFixture worker : workers) {
            if (worker.getWorkerGroupId() == null || worker.getWorkerGroupId().isBlank()) {
                continue;
            }
            groupsByAdapterNode.computeIfAbsent(worker.getAdapterId(), ignored -> new ArrayList<>())
                    .add(worker.getWorkerGroupId());
        }
        for (Map.Entry<String, List<String>> entry : groupsByAdapterNode.entrySet()) {
            String adapterNodeId = entry.getKey();
            runtime.registerAdapterNode(AdapterNodeRegistration.builder()
                    .adapterNodeId(adapterNodeId)
                    .adapterType(adapterNodeId)
                    .endpointId(adapterNodeId)
                    .build());
            for (String groupId : entry.getValue().stream().distinct().toList()) {
                runtime.bindNodeGroup(NodeGroupBindingRegistration.builder()
                        .adapterNodeId(adapterNodeId)
                        .workerGroupId(groupId)
                        .build());
            }
        }
    }

    private void loadRules(MassRuntimeControl runtime) {
        RuleDefinition[] rules = readConfig(ruleConfigPath, RuleDefinition[].class);
        if (rules == null || rules.length == 0) {
            logger.info("No rules config found; keeping existing runtime rules [path={}]", ruleConfigPath);
            return;
        }
        runtime.replaceDefaultRules(List.of(rules));
        logger.info("Loaded {} rules [path={}]", rules.length, ruleConfigPath);
    }

    private void loadTasks(MassRuntimeControl runtime) {
        BootstrapTaskFixture[] dtos = readConfig(taskConfigPath, BootstrapTaskFixture[].class);
        if (dtos == null) return;
        if (dtos.length == 0) {
            logger.info("Task config is empty, no bootstrap tasks [path={}]", taskConfigPath);
            return;
        }
        for (BootstrapTaskFixture dto : dtos) {
            TaskShellSnapshot task = runtime.createTaskShell(toShellCreateRequest(dto));
            if (dto.getInputs() != null && !dto.getInputs().isEmpty()) {
                runtime.appendTaskItems(task.getTaskId(), MassTaskItemBatchAppendRequest.builder()
                        .items(new ArrayList<>(dto.getInputs()))
                        .build());
            }
            if (!dto.isKeepIntakeOpen()) {
                runtime.sealTask(task.getTaskId());
            }
        }
        logger.info("Loaded {} task requests [path={}]", dtos.length, taskConfigPath);
    }

    private <T> T readConfig(String configPath, Class<T> type) {
        if (configPath == null || configPath.isBlank()) return null;
        try {
            String json = readConfigFile(configPath);
            T result = MAPPER.readValue(json, type);
            if (result == null) {
                logger.warn("Config parsed to null [path={}, type={}]", configPath, type.getSimpleName());
            }
            return result;
        } catch (IOException e) {
            logger.debug("Optional config not found, skipping [path={}]", configPath);
            return null;
        } catch (Exception e) {
            logger.warn("Failed to parse config - check JSON format matches plain object array, not DSL [path={}, error={}]",
                    configPath, e.getMessage());
            return null;
        }
    }

    private void normalizeWorker(WorkerFixture worker) {
        if (worker == null) {
            return;
        }
        if (worker.getWorkerGroupId() != null) {
            worker.setWorkerGroupId(worker.getWorkerGroupId().toLowerCase());
        }
        String adapterId = worker.getAdapterId();
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("Worker fixture must declare adapterId: " + worker.getWorkerId());
        }
        worker.setAdapterId(adapterId.trim().toLowerCase(Locale.ROOT));
        String onlineStrategy = worker.getOnlineStrategy();
        if (onlineStrategy == null || onlineStrategy.isBlank()) {
            throw new IllegalArgumentException("Worker fixture must declare onlineStrategy/transportHint: "
                    + worker.getWorkerId());
        }
        worker.setOnlineStrategy(WorkerTransportHints.normalize(onlineStrategy));
        List<String> supportedProjects = worker.getSupportedProjects();
        if (supportedProjects == null || supportedProjects.isEmpty()) {
            worker.setSupportedProjects(List.of("demoApp", "testApp"));
        } else {
            worker.setSupportedProjects(supportedProjects.stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList());
        }
        List<String> supportedEventCodes = worker.getSupportedEventCodes();
        if (supportedEventCodes == null || supportedEventCodes.isEmpty()) {
            worker.setSupportedEventCodes(List.of());
        } else {
            worker.setSupportedEventCodes(supportedEventCodes.stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList());
        }
    }

    private WorkerRegistration toRegistration(WorkerFixture worker) {
        WorkerRegistration.Builder builder = WorkerRegistration.builder()
                .workerId(worker.getWorkerId())
                .workerGroupId(worker.getWorkerGroupId())
                .transportHint(worker.getOnlineStrategy())
                .attributes(worker.getAttributes());
        return builder.build();
    }

    private List<WorkerEventBinding> distinctBindings(List<WorkerEventBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return List.of();
        }
        List<WorkerEventBinding> distinct = bindings.stream().distinct().toList();
        return distinct.isEmpty() ? List.of() : List.copyOf(distinct);
    }

    private List<WorkerEventBinding> toEventBindings(WorkerFixture worker) {
        List<String> supportedEventCodes = worker.getSupportedEventCodes();
        if (supportedEventCodes == null || supportedEventCodes.isEmpty()) {
            return List.of();
        }
        List<String> projectCodes = worker.getSupportedProjects();
        List<WorkerEventBinding> bindings = new ArrayList<>();
        for (String eventCode : supportedEventCodes) {
            if (eventCode == null || eventCode.isBlank()) {
                continue;
            }
            bindings.add(WorkerEventBinding.builder()
                    .eventCode(eventCode)
                    .projectCodes(projectCodes)
                    .build());
        }
        return bindings.isEmpty() ? List.of() : List.copyOf(bindings);
    }

    private String readConfigFile(String configPath) throws IOException {
        if (configPath == null || configPath.isBlank()) {
            throw new IOException("Config path is blank");
        }
        if (configPath.startsWith("classpath:")) {
            String classpathPath = configPath.substring("classpath:".length());
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(classpathPath)) {
                if (is != null) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            throw new IOException("Config file not found in classpath: " + classpathPath);
        }
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(configPath)) {
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        try {
            return Files.readString(Path.of(configPath));
        } catch (IOException e) {
            throw new IOException("Config file not found: " + configPath, e);
        }
    }

    private MassTaskShellCreateRequest toShellCreateRequest(BootstrapTaskFixture dto) {
        TaskExecutionOptions executionSpec = new TaskExecutionOptions();
        executionSpec.setBatchSize(dto.getBatchSize());
        executionSpec.setMaxRuntimeSeconds(dto.getMaxRuntimeSeconds());
        executionSpec.setWorkloadClass(dto.getWorkloadClass());
        return MassTaskShellCreateRequest.builder()
                .userId(dto.getUserId())
                .project(dto.getProject())
                .sharedConfig(dto.getSharedConfig())
                .executionSpec(executionSpec)
                .sourceRef(dto.getSourceRef())
                .build();
    }

    private static final class WorkerFixture {
        private String workerId;
        private String workerGroupId;
        private String adapterId;
        private String onlineStrategy;
        private String agentVersion;
        private List<String> supportedProjects;
        private List<String> supportedEventCodes;
        private java.util.Map<String, String> attributes;

        public String getWorkerId() {
            return workerId;
        }

        public void setWorkerId(String workerId) {
            this.workerId = workerId;
        }

        public String getWorkerGroupId() {
            return workerGroupId;
        }

        public void setWorkerGroupId(String workerGroupId) {
            this.workerGroupId = workerGroupId;
        }

        public String getAdapterId() {
            return adapterId;
        }

        public void setAdapterId(String adapterId) {
            this.adapterId = adapterId;
        }

        public String getOnlineStrategy() {
            return onlineStrategy;
        }

        public void setOnlineStrategy(String onlineStrategy) {
            this.onlineStrategy = onlineStrategy;
        }

        public String getAgentVersion() {
            return agentVersion;
        }

        public void setAgentVersion(String agentVersion) {
            this.agentVersion = agentVersion;
        }

        public List<String> getSupportedProjects() {
            return supportedProjects;
        }

        public void setSupportedProjects(List<String> supportedProjects) {
            this.supportedProjects = supportedProjects;
        }

        public List<String> getSupportedEventCodes() {
            return supportedEventCodes;
        }

        public void setSupportedEventCodes(List<String> supportedEventCodes) {
            this.supportedEventCodes = supportedEventCodes;
        }

        public java.util.Map<String, String> getAttributes() {
            return attributes;
        }

        public void setAttributes(java.util.Map<String, String> attributes) {
            this.attributes = attributes;
        }
    }

    private static final class BootstrapTaskFixture {
        private String userId;
        private String project;
        private java.util.Map<String, Object> sharedConfig;
        private java.util.List<java.util.Map<String, Object>> inputs;
        private int batchSize;
        private boolean keepIntakeOpen;
        private int maxRuntimeSeconds;
        private String workloadClass;
        private String sourceRef;

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getProject() {
            return project;
        }

        public void setProject(String project) {
            this.project = project;
        }

        public java.util.Map<String, Object> getSharedConfig() {
            return sharedConfig;
        }

        public void setSharedConfig(java.util.Map<String, Object> sharedConfig) {
            this.sharedConfig = sharedConfig;
        }

        public java.util.List<java.util.Map<String, Object>> getInputs() {
            return inputs;
        }

        public void setInputs(java.util.List<java.util.Map<String, Object>> inputs) {
            this.inputs = inputs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public boolean isKeepIntakeOpen() {
            return keepIntakeOpen;
        }

        public void setKeepIntakeOpen(boolean keepIntakeOpen) {
            this.keepIntakeOpen = keepIntakeOpen;
        }

        public int getMaxRuntimeSeconds() {
            return maxRuntimeSeconds;
        }

        public void setMaxRuntimeSeconds(int maxRuntimeSeconds) {
            this.maxRuntimeSeconds = maxRuntimeSeconds;
        }

        public String getWorkloadClass() {
            return workloadClass;
        }

        public void setWorkloadClass(String workloadClass) {
            this.workloadClass = workloadClass;
        }

        public String getSourceRef() {
            return sourceRef;
        }

        public void setSourceRef(String sourceRef) {
            this.sourceRef = sourceRef;
        }
    }
}
