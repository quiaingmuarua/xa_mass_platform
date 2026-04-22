package com.xa.mass.mock.bootstrap;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.model.TaskCreateRequestDto;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Dev-app bootstrap data loader.
 *
 * <p>Reads plain JSON config files and registers workers, contexts, rules, and
 * tasks into the SDK runtime. No mock data generation — all definitions are
 * explicit in the config files.
 */
public class MockRuntimeDataLoader implements MassBootstrapDataProvider {

    private static final Logger logger = LoggerFactory.getLogger(MockRuntimeDataLoader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .registerModule(new JavaTimeModule());

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
        loadWorkers(runtime);
        loadWorkerContexts(runtime);
        loadRules(runtime);
        loadTasks(runtime);
        logger.info("Runtime data load completed");
    }

    private void loadWorkers(MassRuntimeControl runtime) {
        Worker[] workers = readConfig(workerConfigPath, Worker[].class);
        if (workers == null) return;
        for (Worker worker : workers) {
            normalizeWorker(worker);
            runtime.addWorker(worker);
        }
        logger.info("Loaded {} workers", workers.length);
    }

    private void loadWorkerContexts(MassRuntimeControl runtime) {
        WorkerContext[] contexts = readConfig(workerContextConfigPath, WorkerContext[].class);
        if (contexts == null) return;
        int accepted = 0;
        for (WorkerContext ctx : contexts) {
            normalizeWorkerContext(ctx);
            if (ctx.getWorkerId() == null || ctx.getWorkerId().isBlank()) {
                logger.warn("Skipping worker context {} - workerId missing", ctx.getWorkerContextId());
                continue;
            }
            runtime.addWorkerContext(ctx);
            accepted++;
        }
        logger.info("Loaded {} worker contexts", accepted);
    }

    private void loadRules(MassRuntimeControl runtime) {
        RuleDefinition[] rules = readConfig(ruleConfigPath, RuleDefinition[].class);
        if (rules == null || rules.length == 0) {
            logger.info("No rules config found; keeping existing runtime rules");
            return;
        }
        runtime.replaceDefaultRules(List.of(rules));
        logger.info("Loaded {} rules", rules.length);
    }

    private void loadTasks(MassRuntimeControl runtime) {
        TaskCreateRequestDto[] dtos = readConfig(taskConfigPath, TaskCreateRequestDto[].class);
        if (dtos == null) return;
        for (TaskCreateRequestDto dto : dtos) {
            runtime.createTask(toSdkRequest(dto));
        }
        logger.info("Loaded {} task requests", dtos.length);
    }

    private <T> T readConfig(String configPath, Class<T> type) {
        if (configPath == null || configPath.isBlank()) return null;
        try {
            String json = readConfigFile(configPath);
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            logger.debug("Optional config not found, skipping [path={}]", configPath);
            return null;
        } catch (Exception e) {
            logger.warn("Failed to load config [path={}]: {}", configPath, e.getMessage());
            return null;
        }
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
