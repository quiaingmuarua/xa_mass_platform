package com.xa.mass.worker.execution;

import com.xa.mass.workerdelivery.json.Jsons;
import java.util.Map;

public final class WorkerEventParameterResolvers {

    private WorkerEventParameterResolvers() {
    }

    public static WorkerEventParameterResolver<String> string() {
        return payload -> requirePayload(payload);
    }

    public static WorkerEventParameterResolver<Map<String, Object>>
    jsonMap() {
        return payload -> Jsons.parseObject(requirePayload(payload));
    }

    private static String requirePayload(String payload) {
        if (payload == null) {
            throw new IllegalArgumentException(
                    "payload must be present"
            );
        }
        return payload;
    }
}
