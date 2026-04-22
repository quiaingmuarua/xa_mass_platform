package com.xa.mass.mock.bootstrap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.monkey.MonkeyGenerator;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.sdk.MassBootstrapDataProvider;
import com.xa.mass.sdk.MassRuntimeControl;
import com.xa.mass.sdk.model.MassTaskCreateRequest;
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
import java.util.Map;
import java.util.Objects;

/**
 * Dev-app owned mock bootstrap loader.
 *
 * <p>The SDK only exposes open runtime registration capabilities. Mock
 * generation and config loading stay outside the SDK module.
 */
public class MockRuntimeDataLoader implements MassBootstrapDataProvider {

    private static final Logger logger = LoggerFactory.getLogger(MockRuntimeDataLoader.class);

    private final String workerConfigPath;
    private final String workerContextConfigPath;
    private final String taskConfigPath;
    private final String ruleConfigPath;

    public MockRuntimeDataLoader(String workerConfigPath,
                                 String workerContextConfigPath,
                                 String taskConfigPath,
                                 String ruleConfigPath) {
        this.workerConfigPath = workerConfigPath;
        this.workerContextConfigPath = workerContextConfigPath;
        this.taskConfigPath = taskConfigPath;
        this.ruleConfigPath = ruleConfigPath;
    }

    @Override
    public void loadInto(MassRuntimeControl runtime) {
        Objects.requireNonNull(runtime, "runtime");
        JsonObject root = loadConfigRoot();
        loadWorkers(runtime, root);
        loadWorkerContexts(runtime, root);
        loadRules(runtime, root);
        loadTasks(runtime, root);
        logger.info("Mock runtime data load completed");
    }

    private JsonObject loadConfigRoot() {
        JsonObject root = new JsonObject();
        addArrayConfig(root, "workers", workerConfigPath);
        addArrayConfig(root, "workerContexts", workerContextConfigPath);
        addArrayConfig(root, "tasks", taskConfigPath);
        addArrayConfig(root, "rules", ruleConfigPath);
        return root;
    }

    private void loadWorkers(MassRuntimeControl runtime, JsonObject root) {
        if (!root.has("workers")) {
            return;
        }
        List<Worker> workers = new ArrayList<>();
        JsonElement workerElem = root.get("workers");
        if (workerElem.isJsonArray()) {
            for (JsonElement dsl : workerElem.getAsJsonArray()) {
                workers.addAll(MonkeyGenerator.generateWorkers(dsl.toString()));
            }
        } else {
            workers.addAll(MonkeyGenerator.generateWorkers(workerElem.toString()));
        }
        for (Worker worker : workers) {
            normalizeWorker(worker);
            runtime.addWorker(worker);
        }
        logger.info("Loaded {} mock workers", workers.size());
    }

    private void loadWorkerContexts(MassRuntimeControl runtime, JsonObject root) {
        if (!root.has("workerContexts")) {
            return;
        }
        List<WorkerContext> workerContexts = new ArrayList<>();
        JsonElement workerContextElem = root.get("workerContexts");
        if (workerContextElem.isJsonArray()) {
            for (JsonElement dsl : workerContextElem.getAsJsonArray()) {
                workerContexts.addAll(MonkeyGenerator.generateWorkerContexts(dsl.toString()));
            }
        } else {
            workerContexts.addAll(MonkeyGenerator.generateWorkerContexts(workerContextElem.toString()));
        }
        int accepted = 0;
        for (WorkerContext workerContext : workerContexts) {
            normalizeWorkerContext(workerContext);
            if (workerContext.getWorkerId() == null || workerContext.getWorkerId().isBlank()) {
                logger.warn("Skipping mock workerContext {} because workerId is missing",
                        workerContext.getWorkerContextId());
                continue;
            }
            runtime.addWorkerContext(workerContext);
            accepted++;
        }
        logger.info("Loaded {} mock worker contexts", accepted);
    }

    private void loadRules(MassRuntimeControl runtime, JsonObject root) {
        if (!root.has("rules")) {
            return;
        }
        List<RuleDefinition> rules = new ArrayList<>();
        JsonElement ruleElem = root.get("rules");
        if (ruleElem.isJsonArray()) {
            for (JsonElement dsl : ruleElem.getAsJsonArray()) {
                rules.addAll(MonkeyGenerator.generateRules(dsl.toString()));
            }
        } else {
            rules.addAll(MonkeyGenerator.generateRules(ruleElem.toString()));
        }
        if (rules.isEmpty()) {
            logger.info("Mock rules config is empty; keeping existing runtime rules");
            return;
        }
        runtime.replaceDefaultRules(rules);
        logger.info("Loaded {} explicit mock rules", rules.size());
    }

    private void loadTasks(MassRuntimeControl runtime, JsonObject root) {
        if (!root.has("tasks")) {
            return;
        }
        List<TaskCreateRequestDto> taskDtos = new ArrayList<>();
        JsonElement taskElem = root.get("tasks");
        if (taskElem.isJsonArray()) {
            for (JsonElement dsl : taskElem.getAsJsonArray()) {
                taskDtos.addAll(MonkeyGenerator.generateTasks(dsl.toString()));
            }
        } else {
            taskDtos.addAll(MonkeyGenerator.generateTasks(taskElem.toString()));
        }
        for (TaskCreateRequestDto dto : taskDtos) {
            runtime.createTask(toSdkRequest(dto));
        }
        logger.info("Loaded {} mock task requests", taskDtos.size());
    }

    private void normalizeWorker(Worker worker) {
        if (worker == null) {
            return;
        }
        if (worker.getWorkerGroupId() != null) {
            worker.setWorkerGroupId(worker.getWorkerGroupId().toLowerCase());
        }
        List<String> supportedProjects = worker.getSupportedProjects();
        if (supportedProjects == null || supportedProjects.isEmpty()) {
            worker.setSupportedProjects(List.of("demoApp", "testApp"));
            return;
        }
        worker.setSupportedProjects(supportedProjects.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList());
    }

    private void normalizeWorkerContext(WorkerContext workerContext) {
        if (workerContext == null) {
            return;
        }
        if (workerContext.getChannel() != null) {
            workerContext.setChannel(workerContext.getChannel().toLowerCase());
        }
        if (workerContext.getStatus() == null) {
            workerContext.setStatus(WorkerContextStatus.IDLE);
        }
        if (!workerContext.getAttributes().isEmpty()) {
            Map<String, String> normalized = new LinkedHashMap<>(workerContext.getAttributes());
            String country = normalized.get("country");
            if (country != null) {
                normalized.put("country", country.toLowerCase());
            }
            workerContext.setAttributes(normalized);
        }
    }

    private void addArrayConfig(JsonObject root, String fieldName, String configPath) {
        try {
            String json = readConfigFile(configPath);
            root.add(fieldName, JsonParser.parseString(json).getAsJsonArray());
        } catch (IOException e) {
            logger.debug("Optional config file not found, skipping [field={}, path={}]", fieldName, configPath);
        } catch (Exception e) {
            logger.warn("Failed to parse config file [field={}, path={}]: {}", fieldName, configPath, e.getMessage());
        }
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
            throw new IOException("Config file not found in classpath or file system: " + configPath, e);
        }
    }

    private MassTaskCreateRequest toSdkRequest(TaskCreateRequestDto dto) {
        return MassTaskCreateRequest.builder()
                .userId(dto.getUserId())
                .project(dto.getProject())
                .taskName(dto.getTaskName())
                .sharedConfig(dto.getSharedConfig())
                .inputs(dto.getInputs())
                .routingCode(dto.getRoutingCode())
                .batchSize(dto.getBatchSize())
                .defaultMsgMaxRetryCount(dto.getDefaultMsgMaxRetryCount())
                .openEnded(dto.isOpenEnded())
                .maxRuntimeSeconds(dto.getMaxRuntimeSeconds())
                .build();
    }
}
