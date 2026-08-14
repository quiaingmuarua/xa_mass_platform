package com.xa.mass.server.scenariorpc;

import com.xa.mass.scenariorpc.ScenarioRpcDescriptor;
import com.xa.mass.scenariorpc.ScenarioRpcEngine;
import com.xa.mass.scenariorpc.ScenarioRpcPollingPolicy;
import com.xa.mass.scenariorpc.ScenarioRpcRunOutcome;
import com.xa.mass.scenariorpc.ScenarioRpcRunStatus;
import com.xa.mass.scenariorpc.ScenarioRpcScenario;
import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcCreateRequest;
import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcCreateResponse;
import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcInputUploadResponse;
import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcInstanceResponse;
import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcRunRequest;
import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcRunResponse;
import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcTypeCatalogResponse;
import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcTypeView;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ScenarioRpcService {

    private static final String CREATE_OPERATION = "scenarioRpc.create";
    private static final String RUN_OPERATION = "scenarioRpc.run";

    private final ScenarioRpcEngine engine;
    private final ScenarioRpcFileStore files;
    private final ScenarioRpcTaskBatchExchange exchange;
    private final ScenarioRpcInstanceRegistry instances;
    private final ScenarioRpcProperties properties;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong lastScenarioMillis = new AtomicLong(-1);

    ScenarioRpcService(
            ScenarioRpcEngine engine,
            ScenarioRpcFileStore files,
            ScenarioRpcTaskBatchExchange exchange,
            ScenarioRpcInstanceRegistry instances,
            ScenarioRpcProperties properties,
            Clock clock
    ) {
        this.engine = engine;
        this.files = files;
        this.exchange = exchange;
        this.instances = instances;
        this.properties = properties;
        this.clock = clock;
    }

    public ScenarioRpcTypeCatalogResponse scenarioTypes() {
        return new ScenarioRpcTypeCatalogResponse(
                engine.scenarioTypes().stream()
                        .map(ScenarioRpcService::typeView)
                        .toList()
        );
    }

    public ScenarioRpcCreateResponse create(
            ScenarioRpcCreateRequest request
    ) {
        if (request == null
                || request.scenarioType() == null
                || request.scenarioType().isBlank()) {
            throw invalid(CREATE_OPERATION, "scenarioType is invalid");
        }
        ScenarioRpcScenario scenario;
        try {
            scenario = engine.createScenario(request.scenarioType());
        } catch (IllegalArgumentException error) {
            throw invalid(CREATE_OPERATION, error.getMessage());
        }
        long scenarioMillis = nextScenarioMillis();
        ScenarioRpcInstanceRegistry.Snapshot created = instances.create(
                "scenario-" + scenarioMillis,
                scenario.descriptor().scenarioType(),
                Instant.ofEpochMilli(scenarioMillis)
        );
        return new ScenarioRpcCreateResponse(
                created.scenarioId(),
                created.scenarioType(),
                created.status().wireValue()
        );
    }

    public ScenarioRpcInputUploadResponse upload(
            String fileName,
            byte[] content
    ) {
        ScenarioRpcFileStore.StoredInput stored = files.upload(
                fileName,
                content
        );
        return new ScenarioRpcInputUploadResponse(
                stored.fileName(),
                stored.byteCount(),
                stored.lineCount()
        );
    }

    public ScenarioRpcInstanceResponse get(String scenarioId) {
        return instanceResponse(instances.get(scenarioId));
    }

    public ScenarioRpcRunResponse run(
            String scenarioId,
            ScenarioRpcRunRequest request
    ) {
        ScenarioRpcPollingPolicy polling = polling(request);
        ScenarioRpcInstanceRegistry.Snapshot instance =
                instances.requireCreated(scenarioId);
        List<String> lines = files.readInput(request.inputFile());
        if (!running.compareAndSet(false, true)) {
            throw conflict();
        }

        long startedNanos = System.nanoTime();
        ScenarioRpcFileStore.OutputSession output = null;
        try {
            instances.begin(scenarioId, request.inputFile());
            output = files.output(scenarioId);
            ScenarioRpcRunOutcome outcome = engine
                    .createScenario(instance.scenarioType())
                    .run(
                            scenarioId,
                            lines,
                            polling,
                            exchange,
                            output
                    );
            boolean partial = outcome.status()
                    == ScenarioRpcRunStatus.PARTIAL;
            String outputFile = output.publish(partial);
            long durationMillis = durationMillis(startedNanos);
            ScenarioRpcInstanceRegistry.Snapshot completed =
                    instances.complete(
                            scenarioId,
                            partial
                                    ? ScenarioRpcInstanceStatus.PARTIAL
                                    : ScenarioRpcInstanceStatus.SUCCEEDED,
                            lines.size(),
                            outcome.results().size(),
                            outcome.remainingCount(),
                            outcome.loadRounds(),
                            durationMillis,
                            outputFile
                    );
            return runResponse(completed);
        } catch (IllegalArgumentException error) {
            instances.fail(scenarioId, durationMillis(startedNanos));
            throw invalid(RUN_OPERATION, error.getMessage());
        } catch (ServerException error) {
            instances.fail(scenarioId, durationMillis(startedNanos));
            if (isScenarioRpcError(error.errorCode())) {
                throw error;
            }
            throw unavailable(error);
        } catch (RuntimeException error) {
            instances.fail(scenarioId, durationMillis(startedNanos));
            throw unavailable(error);
        } finally {
            if (output != null) {
                output.close();
            }
            running.set(false);
        }
    }

    public byte[] download(String fileName) {
        return files.readOutput(fileName);
    }

    private ScenarioRpcPollingPolicy polling(ScenarioRpcRunRequest request) {
        if (request == null
                || request.inputFile() == null
                || request.inputFile().isBlank()) {
            throw invalid(RUN_OPERATION, "inputFile is invalid");
        }
        long interval = request.loadIntervalMillis() == null
                ? properties.defaultLoadIntervalMillis()
                : request.loadIntervalMillis();
        int rounds = request.maximumLoadRounds() == null
                ? properties.defaultMaximumLoadRounds()
                : request.maximumLoadRounds();
        try {
            return new ScenarioRpcPollingPolicy(interval, rounds);
        } catch (IllegalArgumentException error) {
            throw invalid(RUN_OPERATION, error.getMessage());
        }
    }

    private long nextScenarioMillis() {
        return lastScenarioMillis.updateAndGet(
                previous -> Math.max(clock.millis(), previous + 1)
        );
    }

    private static long durationMillis(long startedNanos) {
        return Math.max(
                0,
                (System.nanoTime() - startedNanos) / 1_000_000
        );
    }

    private static ScenarioRpcTypeView typeView(
            ScenarioRpcDescriptor scenario
    ) {
        return new ScenarioRpcTypeView(
                scenario.scenarioType(),
                scenario.workerGroupId(),
                scenario.eventCode()
        );
    }

    private static ScenarioRpcRunResponse runResponse(
            ScenarioRpcInstanceRegistry.Snapshot instance
    ) {
        return new ScenarioRpcRunResponse(
                instance.scenarioId(),
                instance.status().wireValue(),
                instance.inputFile(),
                instance.inputCount(),
                instance.resultCount(),
                instance.remainingCount(),
                instance.loadRounds(),
                instance.durationMillis(),
                instance.outputFile()
        );
    }

    private static ScenarioRpcInstanceResponse instanceResponse(
            ScenarioRpcInstanceRegistry.Snapshot instance
    ) {
        return new ScenarioRpcInstanceResponse(
                instance.scenarioId(),
                instance.scenarioType(),
                instance.status().wireValue(),
                instance.createdAt(),
                instance.inputFile(),
                instance.inputCount(),
                instance.resultCount(),
                instance.remainingCount(),
                instance.loadRounds(),
                instance.durationMillis(),
                instance.outputFile()
        );
    }

    private static boolean isScenarioRpcError(ServerErrorCode errorCode) {
        return errorCode == ServerErrorCode.SCENARIO_RPC_INVALID_REQUEST
                || errorCode
                == ServerErrorCode.SCENARIO_RPC_RESOURCE_NOT_FOUND
                || errorCode == ServerErrorCode.SCENARIO_RPC_CONFLICT
                || errorCode == ServerErrorCode.SCENARIO_RPC_UNAVAILABLE;
    }

    private static ServerException invalid(
            String operation,
            String message
    ) {
        return new ServerException(
                ServerErrorCode.SCENARIO_RPC_INVALID_REQUEST,
                operation,
                message,
                null
        );
    }

    private static ServerException conflict() {
        return new ServerException(
                ServerErrorCode.SCENARIO_RPC_CONFLICT,
                RUN_OPERATION,
                null,
                null
        );
    }

    private static ServerException unavailable(RuntimeException cause) {
        return new ServerException(
                ServerErrorCode.SCENARIO_RPC_UNAVAILABLE,
                RUN_OPERATION,
                null,
                cause
        );
    }
}
