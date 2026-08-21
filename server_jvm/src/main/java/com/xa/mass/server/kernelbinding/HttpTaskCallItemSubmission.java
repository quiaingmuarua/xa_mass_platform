package com.xa.mass.server.kernelbinding;

import com.xa.mass.kernel.task.TaskCallItemSubmission;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendStatus;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HttpTaskCallItemSubmission
        implements TaskCallItemSubmission {

    private final PythonKernelHttpTransport transport;

    public HttpTaskCallItemSubmission(
            PythonKernelHttpTransport transport
    ) {
        this.transport = transport;
    }

    @Override
    public TaskCallSubmissionResult submit(
            String taskId,
            List<TaskItem> items
    ) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must be non-blank");
        }
        if (items == null || items.isEmpty() || items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException(
                    "Task Call submission requires 1..100 Items"
            );
        }

        List<Map<String, Object>> encodedItems = new ArrayList<>(items.size());
        for (TaskItem item : items) {
            var encoded = new LinkedHashMap<String, Object>();
            encoded.put("messageId", item.messageId());
            encoded.put("eventCode", item.eventCode());
            encoded.put("createdAtMillis", item.createdAtMillis());
            encoded.put("payload", item.payload());
            encoded.put("priority", item.priority());
            encoded.put("expireAtMillis", item.expireAtMillis());
            encoded.put("allocationRule", item.allocationRule());
            encodedItems.add(encoded);
        }

        Map<String, Object> response = transport.postBody(
                "/tasks/{taskId}:submit-call-items",
                Map.of("items", encodedItems),
                taskId
        );
        return new TaskCallSubmissionResult(
                KernelHttpResultDecoder.status(
                        response,
                        TaskCallSubmissionStatus::fromWireValue
                ),
                decodeItemResults(response),
                KernelHttpResultDecoder.reason(response)
        );
    }

    private static Map<String, TaskItemAppendResult> decodeItemResults(
            Map<String, Object> response
    ) {
        Object rawResults = response.get("itemResults");
        if (!(rawResults instanceof Map<?, ?> results)) {
            throw invalidResponse("Kernel itemResults are missing or invalid");
        }
        var decoded = new LinkedHashMap<String, TaskItemAppendResult>();
        for (Map.Entry<?, ?> entry : results.entrySet()) {
            if (!(entry.getKey() instanceof String messageId)
                    || !(entry.getValue() instanceof Map<?, ?> value)) {
                throw invalidResponse("Kernel Item result is invalid");
            }
            Object rawStatus = value.get("status");
            if (!(rawStatus instanceof String statusValue)) {
                throw invalidResponse("Kernel Item status is invalid");
            }
            TaskItemAppendStatus status = appendStatus(statusValue);
            Object rawReason = value.get("reason");
            if (rawReason != null && !(rawReason instanceof String)) {
                throw invalidResponse("Kernel Item reason is invalid");
            }
            decoded.put(
                    messageId,
                    new TaskItemAppendResult(status, (String) rawReason)
            );
        }
        return decoded;
    }

    private static TaskItemAppendStatus appendStatus(String value) {
        for (TaskItemAppendStatus status : TaskItemAppendStatus.values()) {
            if (status.wireValue().equals(value)) {
                return status;
            }
        }
        throw invalidResponse("Kernel Item status is unknown");
    }

    private static ServerException invalidResponse(String message) {
        return new ServerException(
                ServerErrorCode.INVALID_KERNEL_RESPONSE,
                "kernelBinding.decodeTaskCallSubmission",
                message,
                null
        );
    }
}
