package com.xa.mass.server.kernelbinding;

import com.xa.mass.kernel.worker.WorkerRuntime;
import java.util.LinkedHashMap;
import java.util.Map;

public final class HttpWorkerRuntime implements WorkerRuntime {

    private final PythonKernelHttpTransport transport;

    public HttpWorkerRuntime(PythonKernelHttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public WorkerRuntimeResult upsertWorker(
            WorkerDeclaration declaration
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("endpointManagerId", declaration.endpointManagerId());
        body.put("attributes", declaration.attributes());
        body.put(
                "dynamicAttributeNames",
                declaration.dynamicAttributeNames()
        );
        Map<String, Object> response = transport.put(
                "/worker-groups/{workerGroupId}/workers/{workerId}",
                body,
                declaration.workerGroupId(),
                declaration.workerId()
        );
        return result(response);
    }

    static WorkerRuntimeResult result(Map<String, Object> response) {
        WorkerRuntimeStatus status = KernelHttpResultDecoder.status(
                response,
                HttpWorkerRuntime::status
        );
        return new WorkerRuntimeResult(
                status,
                KernelHttpResultDecoder.reason(response)
        );
    }

    private static WorkerRuntimeStatus status(String value) {
        for (WorkerRuntimeStatus status : WorkerRuntimeStatus.values()) {
            if (status.wireValue().equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown Worker runtime status");
    }
}
