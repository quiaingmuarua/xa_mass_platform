package com.xa.mass.transport;

import java.util.List;

/**
 * Optional observability seam for endpoint-aware transports.
 */
public interface WorkerEndpointInspector {

    List<WorkerEndpointSnapshot> listWorkerEndpoints();
}
