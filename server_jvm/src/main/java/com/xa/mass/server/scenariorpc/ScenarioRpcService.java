package com.xa.mass.server.scenariorpc;

import com.xa.mass.scenariorpc.ScenarioRpcCall;
import com.xa.mass.scenariorpc.ScenarioRpcDescriptor;
import com.xa.mass.scenariorpc.ScenarioRpcEngine;
import com.xa.mass.scenariorpc.ScenarioRpcResult;
import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcCatalogResponse;
import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcDescriptorView;
import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcInputUploadResponse;
import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcRunRequest;
import com.xa.mass.server.api.v1.scenariorpc.model.ScenarioRpcRunResponse;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ScenarioRpcService {

    private static final String RUN_OPERATION = "scenarioRpc.run";

    private final ScenarioRpcEngine engine;
    private final ScenarioRpcFileStore files;
    private final ScenarioRpcCall rpc;
    private final Clock clock;
    private final List<ScenarioRpcDescriptor> scenarios;
    private final Map<String, ScenarioRpcDescriptor> scenariosById;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong lastExecutionMillis = new AtomicLong(-1);

    ScenarioRpcService(
            ScenarioRpcEngine engine,
            ScenarioRpcFileStore files,
            ScenarioRpcCall rpc,
            Clock clock
    ) {
        this.engine = engine;
        this.files = files;
        this.rpc = rpc;
        this.clock = clock;
        scenarios = engine.scenarios();
        Map<String, ScenarioRpcDescriptor> indexed = new LinkedHashMap<>();
        for (ScenarioRpcDescriptor scenario : scenarios) {
            indexed.put(scenario.scenarioId(), scenario);
        }
        scenariosById = Map.copyOf(indexed);
    }

    public ScenarioRpcCatalogResponse scenarios() {
        return new ScenarioRpcCatalogResponse(scenarios.stream()
                .map(scenario -> new ScenarioRpcDescriptorView(
                        scenario.scenarioId(),
                        scenario.workerGroupId(),
                        scenario.eventCode()
                ))
                .toList());
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

    public ScenarioRpcRunResponse run(ScenarioRpcRunRequest request) {
        if (request == null
                || request.scenarioId() == null
                || request.scenarioId().isBlank()
                || request.inputFile() == null
                || request.inputFile().isBlank()
                || request.concurrency() < 1
                || request.concurrency() > 100) {
            throw invalid("scenarioId, inputFile, or concurrency is invalid");
        }
        ScenarioRpcDescriptor scenario = scenariosById.get(
                request.scenarioId()
        );
        if (scenario == null) {
            throw invalid("unknown scenarioId");
        }
        if (!running.compareAndSet(false, true)) {
            throw new ServerException(
                    ServerErrorCode.SCENARIO_RPC_CONFLICT,
                    RUN_OPERATION,
                    null,
                    null
            );
        }
        try {
            List<String> lines = files.readInput(request.inputFile());
            long executionMillis = nextExecutionMillis();
            long startedNanos = System.nanoTime();
            List<ScenarioRpcResult> results;
            try {
                results = engine.run(
                        scenario.scenarioId(),
                        "rpc-" + executionMillis,
                        lines,
                        request.concurrency(),
                        rpc
                );
            } catch (IllegalArgumentException error) {
                throw new ServerException(
                        ServerErrorCode.SCENARIO_RPC_INVALID_REQUEST,
                        RUN_OPERATION,
                        error.getMessage(),
                        error
                );
            } catch (RuntimeException error) {
                throw unavailable(error);
            }
            String outputFile = scenario.scenarioId()
                    + "-"
                    + executionMillis
                    + ".jsonl";
            files.publishOutput(outputFile, results);
            long durationMillis = Math.max(
                    0,
                    (System.nanoTime() - startedNanos) / 1_000_000
            );
            return new ScenarioRpcRunResponse(
                    scenario.scenarioId(),
                    scenario.workerGroupId(),
                    scenario.eventCode(),
                    request.inputFile(),
                    outputFile,
                    lines.size(),
                    results.size(),
                    durationMillis,
                    Instant.ofEpochMilli(executionMillis)
            );
        } finally {
            running.set(false);
        }
    }

    public byte[] download(String fileName) {
        return files.readOutput(fileName);
    }

    private long nextExecutionMillis() {
        return lastExecutionMillis.updateAndGet(
                previous -> Math.max(clock.millis(), previous + 1)
        );
    }

    private static ServerException invalid(String message) {
        return new ServerException(
                ServerErrorCode.SCENARIO_RPC_INVALID_REQUEST,
                RUN_OPERATION,
                message,
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
