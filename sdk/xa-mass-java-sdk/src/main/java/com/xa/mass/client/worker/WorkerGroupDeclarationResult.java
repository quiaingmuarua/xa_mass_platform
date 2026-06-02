package com.xa.mass.client.worker;

import java.util.List;
import java.util.Map;

public record WorkerGroupDeclarationResult(
        String groupId,
        List<WorkerEventBindingSpec> eventBindings,
        Map<String, String> defaultAttributes,
        int defaultMaxConcurrentWork
) {
}
