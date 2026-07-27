package com.xa.mass.server.kernelbinding;

import java.util.Map;

public final class HttpTaskLifecycleCommands
        implements TaskLifecycleCommands {

    private final PythonKernelHttpTransport transport;

    public HttpTaskLifecycleCommands(PythonKernelHttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public TaskApprovalResult approveTask(String taskId) {
        Map<String, Object> response = transport.post(
                "/tasks/{taskId}/approve",
                taskId
        );
        return new TaskApprovalResult(
                KernelHttpResultDecoder.status(
                        response,
                        TaskApprovalStatus::fromWireValue
                ),
                KernelHttpResultDecoder.reason(response)
        );
    }

    @Override
    public TaskCloseResult closeTask(String taskId) {
        Map<String, Object> response = transport.post(
                "/tasks/{taskId}/close",
                taskId
        );
        return new TaskCloseResult(
                KernelHttpResultDecoder.status(
                        response,
                        TaskCloseStatus::fromWireValue
                ),
                KernelHttpResultDecoder.reason(response)
        );
    }
}
