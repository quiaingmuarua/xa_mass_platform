package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.api.model.worker.WorkerSendMessageRequest;
import com.xa.mass.sdk.DebugOperations;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.Map;

@Controller
@RequestMapping("/status/workers")
public class WorkerDebugController {

    private final DebugOperations debugOperations;

    public WorkerDebugController(DebugOperations debugOperations) {
        this.debugOperations = debugOperations;
    }

    @GetMapping("/message-history")
    @ResponseBody
    public ApiResponse<Map<String, Object>> getWorkerMessageHistory(
            @org.springframework.web.bind.annotation.RequestParam String workerId) {
        return ApiResponse.success(Map.of(
                "workerId", workerId,
                "items", debugOperations.getWorkerMessageHistory(workerId)
        ));
    }

    @PostMapping("/send-message")
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<String, Object>>> sendWorkerMessage(
            @RequestBody WorkerSendMessageRequest requestBody) {
        try {
            validateKnownFields(requestBody);
            String workerId = readTrimmed(requestBody.getWorkerId());
            if (workerId == null) {
                return badRequest("workerId is required");
            }
            Map<String, Object> result = debugOperations.sendWorkerMessage(
                    workerId,
                    readTrimmed(requestBody.getProject()),
                    readTrimmed(requestBody.getMsgType()),
                    readTrimmed(requestBody.getSubMsgType()),
                    requestBody.getPayload()
            );
            return ok(result);
        } catch (IllegalArgumentException ex) {
            String message = ex.getMessage() == null ? "Invalid request" : ex.getMessage();
            if ("Worker not found".equals(message)) {
                return notFound(message);
            }
            return badRequest(message);
        } catch (IllegalStateException ex) {
            return conflict(ex.getMessage());
        }
    }

    private void validateKnownFields(WorkerSendMessageRequest requestBody) {
        if (requestBody == null) {
            throw new IllegalArgumentException("worker message request body is required");
        }
        if (requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported worker message fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
        }
    }

    private String readTrimmed(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> ok(Map<String, ?> data) {
        return ResponseEntity.ok(ApiResponse.success(new LinkedHashMap<>(data)));
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> badRequest(String message) {
        return ResponseEntity.badRequest().body(ApiResponse.error(400, message));
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> conflict(String message) {
        return ResponseEntity.status(409).body(ApiResponse.error(409, message));
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> notFound(String message) {
        return ResponseEntity.status(404).body(ApiResponse.error(404, message));
    }
}
