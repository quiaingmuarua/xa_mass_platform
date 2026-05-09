package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.sdk.internal.TransportDebugOperations;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/runtime/queues")
@Tag(name = "Queue Status")
public class QueueController {

    private static final Logger log = LoggerFactory.getLogger(QueueController.class);
    private final TransportDebugOperations transportDebugOperations;

    public QueueController(TransportDebugOperations transportDebugOperations) {
        this.transportDebugOperations = transportDebugOperations;
    }

    @GetMapping("")
    @Operation(summary = "Get detailed queue availability data")
    public ApiResponse<Map<String, Object>> getQueueDetail() {
        log.info("[QueueController] /api/v1/runtime/queues requested");
        return ApiResponse.success(transportDebugOperations.getQueueDetail());
    }

    @GetMapping("/metrics")
    @Operation(summary = "Get reserved queue metrics")
    public ApiResponse<Map<String, Object>> getQueueMetrics() {
        return ApiResponse.success(transportDebugOperations.getQueueMetrics());
    }
}
