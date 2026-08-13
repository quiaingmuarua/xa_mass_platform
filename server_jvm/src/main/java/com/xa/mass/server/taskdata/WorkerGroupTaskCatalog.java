package com.xa.mass.server.taskdata;

import org.jspecify.annotations.Nullable;

public interface WorkerGroupTaskCatalog {

    @Nullable String taskIdFor(String workerGroupId);
}
