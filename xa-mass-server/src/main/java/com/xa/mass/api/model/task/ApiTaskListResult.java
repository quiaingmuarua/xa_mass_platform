package com.xa.mass.api.model.task;

import java.util.List;

public record ApiTaskListResult(
        List<ApiTask> items,
        int total
) {
}
