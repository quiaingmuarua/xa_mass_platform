package com.xa.mass.server.api.v1.contract.worker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record WorkerGroupRegisterRequest(
        Map<String, Object> attributes,
        List<String> eventCodes
) {
    public WorkerGroupRegisterRequest {
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(
                        new LinkedHashMap<>(attributes)
                );
        if (eventCodes != null) {
            eventCodes = Collections.unmodifiableList(
                    new ArrayList<>(eventCodes)
            );
        }
    }
}
