package com.xa.mass.client.worker;

import java.util.List;

public record WorkerEventBindingSpec(String eventCode, List<String> projectCodes) {
    public WorkerEventBindingSpec {
        projectCodes = WorkerRequestSupport.copyList(projectCodes);
    }

    public static WorkerEventBindingSpec of(String eventCode, List<String> projectCodes) {
        return new WorkerEventBindingSpec(eventCode, projectCodes);
    }

    public static WorkerEventBindingSpec event(String eventCode) {
        return new WorkerEventBindingSpec(eventCode, List.of());
    }
}
