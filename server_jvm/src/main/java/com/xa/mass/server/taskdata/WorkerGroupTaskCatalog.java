package com.xa.mass.server.taskdata;

import java.util.Map;

public interface WorkerGroupTaskCatalog {

    Map<String, String> taskIdsByWorkerGroup();
}
