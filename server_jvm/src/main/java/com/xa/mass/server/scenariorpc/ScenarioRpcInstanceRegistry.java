package com.xa.mass.server.scenariorpc;

import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

final class ScenarioRpcInstanceRegistry {

    private static final String OPERATION = "scenarioRpc.instance";

    private final int maximumInstances;
    private final Map<String, MutableInstance> instances =
            new LinkedHashMap<>();

    ScenarioRpcInstanceRegistry(int maximumInstances) {
        if (maximumInstances < 1) {
            throw new IllegalArgumentException(
                    "maximumInstances must be positive"
            );
        }
        this.maximumInstances = maximumInstances;
    }

    synchronized Snapshot create(
            String scenarioId,
            String scenarioType,
            Instant createdAt
    ) {
        evictTerminalIfFull();
        MutableInstance instance = new MutableInstance(
                scenarioId,
                scenarioType,
                createdAt
        );
        instances.put(scenarioId, instance);
        return instance.snapshot();
    }

    synchronized Snapshot get(String scenarioId) {
        return require(scenarioId).snapshot();
    }

    synchronized Snapshot requireCreated(String scenarioId) {
        MutableInstance instance = require(scenarioId);
        if (instance.status != ScenarioRpcInstanceStatus.CREATED) {
            throw conflict();
        }
        return instance.snapshot();
    }

    synchronized void begin(String scenarioId, String inputFile) {
        MutableInstance instance = require(scenarioId);
        if (instance.status != ScenarioRpcInstanceStatus.CREATED) {
            throw conflict();
        }
        instance.status = ScenarioRpcInstanceStatus.RUNNING;
        instance.inputFile = inputFile;
    }

    synchronized Snapshot complete(
            String scenarioId,
            ScenarioRpcInstanceStatus status,
            int inputCount,
            int resultCount,
            int remainingCount,
            int loadRounds,
            long durationMillis,
            String outputFile
    ) {
        if (status != ScenarioRpcInstanceStatus.SUCCEEDED
                && status != ScenarioRpcInstanceStatus.PARTIAL) {
            throw new IllegalArgumentException(
                    "completion status must be succeeded or partial"
            );
        }
        MutableInstance instance = requireRunning(scenarioId);
        instance.status = status;
        instance.inputCount = inputCount;
        instance.resultCount = resultCount;
        instance.remainingCount = remainingCount;
        instance.loadRounds = loadRounds;
        instance.durationMillis = durationMillis;
        instance.outputFile = outputFile;
        return instance.snapshot();
    }

    synchronized void fail(String scenarioId, long durationMillis) {
        MutableInstance instance = require(scenarioId);
        if (instance.status == ScenarioRpcInstanceStatus.RUNNING) {
            instance.status = ScenarioRpcInstanceStatus.FAILED;
            instance.durationMillis = durationMillis;
        }
    }

    private MutableInstance requireRunning(String scenarioId) {
        MutableInstance instance = require(scenarioId);
        if (instance.status != ScenarioRpcInstanceStatus.RUNNING) {
            throw conflict();
        }
        return instance;
    }

    private MutableInstance require(String scenarioId) {
        MutableInstance instance = instances.get(scenarioId);
        if (instance == null) {
            throw new ServerException(
                    ServerErrorCode.SCENARIO_RPC_RESOURCE_NOT_FOUND,
                    OPERATION,
                    null,
                    null
            );
        }
        return instance;
    }

    private void evictTerminalIfFull() {
        if (instances.size() < maximumInstances) {
            return;
        }
        Iterator<MutableInstance> candidates = instances.values().iterator();
        while (candidates.hasNext()) {
            if (candidates.next().status.terminal()) {
                candidates.remove();
                return;
            }
        }
        throw conflict();
    }

    private static ServerException conflict() {
        return new ServerException(
                ServerErrorCode.SCENARIO_RPC_CONFLICT,
                OPERATION,
                null,
                null
        );
    }

    record Snapshot(
            String scenarioId,
            String scenarioType,
            ScenarioRpcInstanceStatus status,
            Instant createdAt,
            String inputFile,
            int inputCount,
            int resultCount,
            int remainingCount,
            int loadRounds,
            long durationMillis,
            String outputFile
    ) {
    }

    private static final class MutableInstance {
        private final String scenarioId;
        private final String scenarioType;
        private final Instant createdAt;
        private ScenarioRpcInstanceStatus status =
                ScenarioRpcInstanceStatus.CREATED;
        private String inputFile;
        private int inputCount;
        private int resultCount;
        private int remainingCount;
        private int loadRounds;
        private long durationMillis;
        private String outputFile;

        private MutableInstance(
                String scenarioId,
                String scenarioType,
                Instant createdAt
        ) {
            this.scenarioId = scenarioId;
            this.scenarioType = scenarioType;
            this.createdAt = createdAt;
        }

        private Snapshot snapshot() {
            return new Snapshot(
                    scenarioId,
                    scenarioType,
                    status,
                    createdAt,
                    inputFile,
                    inputCount,
                    resultCount,
                    remainingCount,
                    loadRounds,
                    durationMillis,
                    outputFile
            );
        }
    }
}
