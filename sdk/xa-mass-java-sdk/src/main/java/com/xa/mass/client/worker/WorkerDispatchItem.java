package com.xa.mass.client.worker;

import java.util.Map;

public record WorkerDispatchItem(
        String resultCorrelationRef,
        String eventCode,
        Map<String, Object> input,
        Map<String, Object> sharedConfig
) {
    public WorkerDispatchItem {
        resultCorrelationRef = requireText(resultCorrelationRef, "resultCorrelationRef");
        input = WorkerRequestSupport.copyObjectMap(input);
        sharedConfig = WorkerRequestSupport.copyObjectMap(sharedConfig);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }
}
