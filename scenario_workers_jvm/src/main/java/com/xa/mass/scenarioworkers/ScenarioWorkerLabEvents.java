package com.xa.mass.scenarioworkers;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class ScenarioWorkerLabEvents {

    static final String CHECKPOINT_EVENT_CODE =
            "extension.worker.lab.checkpoint";

    private static final String CHECKPOINT_CAPABILITY = "lab.checkpoint";
    private static final Set<String> CHECKPOINT_FIELDS = Set.of(
            "checkpointToken"
    );

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
}
