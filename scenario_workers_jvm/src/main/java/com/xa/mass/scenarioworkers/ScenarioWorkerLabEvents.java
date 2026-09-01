package com.xa.mass.scenarioworkers;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ScenarioWorkerLabEvents {

    static final String CHECKPOINT_EVENT_CODE =
            "extension.worker.lab.checkpoint";
    static final String DELAY_EVENT_CODE =
            "extension.worker.lab.delay";
    static final String FAIL_EVENT_CODE =
            "extension.worker.lab.fail";

    private static final String CHECKPOINT_CAPABILITY = "lab.checkpoint";
    private static final String DELAY_CAPABILITY = "lab.delay";
    private static final String FAIL_CAPABILITY = "lab.fail";
    private static final long MAX_DELAY_MILLIS = 30_000L;
    private static final Set<String> CHECKPOINT_FIELDS = Set.of(
            "checkpointToken"
    );
    private static final Set<String> DELAY_FIELDS = Set.of("delayMillis");

    private ScenarioWorkerLabEvents() {
    }

    static WorkerEventDefinition<Map<String, Object>> checkpoint(
            ScenarioWorkerCommandCheckpoints checkpoints
    ) {
        return WorkerEventDefinition.extension(
                CHECKPOINT_CAPABILITY,
                WorkerEventParameterResolvers.jsonMap(),
                payload -> execute(checkpoints, payload)
        );
    }

    static List<WorkerEventDefinition<?>> backgroundFaults() {
        return List.of(delay(), fail());
    }

    private static WorkerEventDefinition<Map<String, Object>> delay() {
        return WorkerEventDefinition.extension(
                DELAY_CAPABILITY,
                WorkerEventParameterResolvers.jsonMap(),
                ScenarioWorkerLabEvents::delay
        );
    }

    private static WorkerEventDefinition<Map<String, Object>> fail() {
        return WorkerEventDefinition.extension(
                FAIL_CAPABILITY,
                WorkerEventParameterResolvers.jsonMap(),
                ScenarioWorkerLabEvents::fail
        );
    }

    private static String execute(
            ScenarioWorkerCommandCheckpoints checkpoints,
            Map<String, Object> payload
    ) {
        if (!payload.keySet().equals(CHECKPOINT_FIELDS)
                || !(payload.get("checkpointToken") instanceof String token)
                || token.isBlank()) {
            throw new WorkerException(
                    WorkerErrorCode.EVENT_INPUT_INVALID,
                    "checkpoint.resolve",
                    "checkpointToken must be the only non-blank field",
                    null
            );
        }
        String disposition = checkpoints.awaitIfArmed(token);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkpoint", disposition);
        return Jsons.toJson(result);
    }

    private static String delay(Map<String, Object> payload) {
        if (!payload.keySet().equals(DELAY_FIELDS)
                || !(payload.get("delayMillis") instanceof Long delayMillis)
                || delayMillis < 1L
                || delayMillis > MAX_DELAY_MILLIS) {
            throw invalidInput(
                    "lab.delay",
                    "delayMillis must be the only integer field in 1..30000"
            );
        }
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new WorkerException(
                    WorkerErrorCode.EVENT_EXECUTION_FAILED,
                    "lab.delay",
                    "Scenario Lab delay was interrupted",
                    error
            );
        }
        return "null";
    }

    private static String fail(Map<String, Object> payload) {
        if (!payload.isEmpty()) {
            throw invalidInput(
                    "lab.fail",
                    "fail payload must be an empty object"
            );
        }
        throw new WorkerException(
                WorkerErrorCode.EVENT_EXECUTION_FAILED,
                "lab.fail",
                "Scenario Lab requested Handler failure",
                null
        );
    }

    private static WorkerException invalidInput(
            String operation,
            String message
    ) {
        return new WorkerException(
                WorkerErrorCode.EVENT_INPUT_INVALID,
                operation,
                message,
                null
        );
    }
}
