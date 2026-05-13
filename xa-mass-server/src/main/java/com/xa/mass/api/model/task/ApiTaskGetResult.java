package com.xa.mass.api.model.task;

import java.util.Map;

public record ApiTaskGetResult(
        ApiTask task,
        Map<String, Object> security
) {
}
