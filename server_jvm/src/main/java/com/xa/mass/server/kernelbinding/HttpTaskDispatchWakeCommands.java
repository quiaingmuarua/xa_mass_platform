package com.xa.mass.server.kernelbinding;

import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HttpTaskDispatchWakeCommands
        implements TaskDispatchWakeCommands {

    private final PythonKernelHttpTransport transport;

    public HttpTaskDispatchWakeCommands(
            PythonKernelHttpTransport transport
    ) {
        this.transport = transport;
    }

    @Override
    public void wakeTaskDispatch(List<String> taskIds) {
        if (taskIds == null
                || taskIds.isEmpty()
                || taskIds.size() > 100
                || taskIds.stream().anyMatch(
                        taskId -> taskId == null || taskId.isBlank()
                )) {
            throw new IllegalArgumentException(
                    "wake taskIds must contain 1..100 non-blank ids"
            );
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskIds", List.copyOf(taskIds));
        Map<String, Object> response = transport.postBody(
                "/tasks:dispatch-wake",
                body
        );
        if (!"accepted".equals(response.get("status"))) {
            throw new ServerException(
                    ServerErrorCode.INVALID_KERNEL_RESPONSE,
                    "kernelBinding.wakeTaskDispatch",
                    "Kernel wake response has an invalid status",
                    null
            );
        }
    }
}
