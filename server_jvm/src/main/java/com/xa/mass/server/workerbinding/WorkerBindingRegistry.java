package com.xa.mass.server.workerbinding;

interface WorkerBindingRegistry {

    String bindIfAbsent(String workerId, String endpointManagerId);

    String getEndpointManagerId(String workerId);
}
