package com.xa.mass.mock.bootstrap;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.sdk.MassBootstrapDataProvider;
import com.xa.mass.sdk.MassRuntimeControl;
import com.xa.mass.sdk.model.MassTaskCreateRequest;
import com.xa.mass.sdk.model.WorkerContextRegistration;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.transport.WorkerTransportHints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Dev-app fixture data loader.
 *
 * <p>Reads plain JSON config files and registers workers, contexts, rules, and
 * tasks into the SDK runtime. No mock data generation - all definitions are
 * explicit in the config files.
 *
 * <p>JSON is only a fixture input format here. Worker and worker-context
 * resources are always created through SDK registration APIs; runtime state
 * fields in old fixture JSON are ignored.
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
        logger.info("Loading bootstrap data [workers={}, contexts={}, rules={}, tasks={}]",
                workerConfigPath, workerContextConfigPath, ruleConfigPath, taskConfigPath);
        loadWorkers(runtime);
        loadWorkerContexts(runtime);
        loadRules(runtime);
        loadTasks(runtime);
        logger.info("Runtime data load completed");
    }

    private void loadWorkers(MassRuntimeControl runtime) {
        Worker[] workers = readConfig(workerConfigPath, Worker[].class);
        if (workers == null) return;
        if (workers.length == 0) {
            logger.warn("Worker config loaded but produced 0 entries [path={}]", workerConfigPath);
            return;
        }
        int accepted = 0;
        for (Worker worker : workers) {
            if (worker == null || worker.getWorkerId() == null || worker.getWorkerId().isBlank()) {
                logger.warn("Skipping worker fixture because workerId is missing");
                continue;
            }
            normalizeWorker(worker);
            runtime.registerWorker(toRegistration(worker));
            accepted++;
        }
        logger.info("Loaded {} workers via SDK registration [path={}]", accepted, workerConfigPath);
    }

    private void loadWorkerContexts(MassRuntimeControl runtime) {
        WorkerContext[] contexts = readConfig(workerContextConfigPath, WorkerContext[].class);
        if (contexts == null) return;
        if (contexts.length == 0) {
            logger.info("Worker context config is empty, workers will run stateless [path={}]", workerContextConfigPath);
            return;
        }
        int accepted = 0;
        for (WorkerContext ctx : contexts) {
            normalizeWorkerContext(ctx);
            if (ctx.getWorkerId() == null || ctx.getWorkerId().isBlank()) {
                logger.warn("Skipping worker context {} - workerId missing", ctx.getWorkerContextId());
                continue;
            }
            runtime.registerWorkerContext(toRegistration(ctx));
            accepted++;
        }
        logger.info("Loaded {} worker contexts via SDK registration [path={}]", accepted, workerContextConfigPath);
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
        TaskCreateRequestDto[] dtos = readConfig(taskConfigPath, TaskCreateRequestDto[].class);
        if (dtos == null) return;
        if (dtos.length == 0) {
            logger.info("Task config is empty, no bootstrap tasks [path={}]", taskConfigPath);
            return;
        }
        for (TaskCreateRequestDto dto : dtos) {
            runtime.createTask(toSdkRequest(dto));
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

    private void normalizeWorker(Worker worker) {
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

    private void normalizeWorkerContext(WorkerContext workerContext) {
        if (workerContext == null) {
            return;
        }
        if (workerContext.getRoutingTags() != null && !workerContext.getRoutingTags().isEmpty()) {
            workerContext.setRoutingTags(
                    workerContext.getRoutingTags().stream()
                            .filter(Objects::nonNull)
                            .map(String::toLowerCase)
                            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new))
            );
        }
    }

    private WorkerRegistration toRegistration(Worker worker) {
        return WorkerRegistration.builder()
                .workerId(worker.getWorkerId())
                .workerGroupId(worker.getWorkerGroupId())
                .supportedProjects(worker.getSupportedProjects())
                .supportedEventCodes(worker.getSupportedEventCodes())
                .adapterId(worker.getAdapterId())
                .transportHint(worker.getOnlineStrategy())
                .attributes(worker.getAttributes())
                .build();
    }

    private WorkerContextRegistration toRegistration(WorkerContext workerContext) {
        WorkerContextRegistration.Builder builder = WorkerContextRegistration.builder()
                .workerContextId(workerContext.getWorkerContextId())
                .workerId(workerContext.getWorkerId())
                .routingTags(workerContext.getRoutingTags())
                .attributes(workerContext.getAttributes());
        if (workerContext.getProject() != null && !workerContext.getProject().isBlank()) {
            builder.project(workerContext.getProject());
        }
        return builder.build();
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
                .batchSize(dto.getBatchSize())
                .defaultMsgMaxRetryCount(dto.getDefaultMsgMaxRetryCount())
                .openEnded(dto.isOpenEnded())
                .maxRuntimeSeconds(dto.getMaxRuntimeSeconds())
                .build();
    }
}
